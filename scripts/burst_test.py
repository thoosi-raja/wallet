#!/usr/bin/env python3
"""Exercise real HTTP concurrency; store evidence and never retry a failed transfer silently."""
import argparse
import collections
import concurrent.futures
import datetime
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time
import urllib.parse
import uuid
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SEED = 1_000_000


def require(condition, message):
    if not condition:
        raise AssertionError(message)


class Runner:
    def __init__(self, args, state):
        self.args, self.state = args, state
        self.directory = Path(state['directory'])
        history = self.directory / 'responses.jsonl'
        self.responses = [json.loads(line) for line in history.read_text().splitlines()] if history.exists() else []
        self.guard = threading.Lock()

    def request(self, method, path, user=None, payload=None, label='read', timeout=120):
        correlation = self.state['run_id'] + '-' + uuid.uuid4().hex[:10]
        headers = {'X-Correlation-ID': correlation}
        if user:
            headers['Authorization'] = 'Bearer ' + user
        start = time.monotonic()
        with tempfile.TemporaryDirectory(prefix='wallet-http-') as tmp:
            response_headers, response_body = Path(tmp) / 'headers', Path(tmp) / 'body'
            command = ['curl', '-sS', '--connect-timeout', '15', '--max-time', str(timeout),
                       '-X', method, '-D', str(response_headers), '-o', str(response_body), '-w', '%{http_code}']
            if payload is not None:
                headers['Content-Type'] = 'application/json'
                command += ['--data', json.dumps(payload)]
            for key, value in headers.items():
                command += ['-H', key + ': ' + value]
            command.append(self.state['base_url'] + path)
            try:
                response = subprocess.run(command, capture_output=True, text=True, timeout=timeout + 5)
                selected_headers = {}
                if response_headers.exists():
                    for line in response_headers.read_text().splitlines():
                        key, separator, value = line.partition(':')
                        if separator and key.lower() in ('x-correlation-id', 'idempotent-replay', 'location'):
                            selected_headers[key.lower()] = value.strip()
                result = {'status': int(response.stdout or '0') if response.returncode == 0 else 0,
                          'body': response_body.read_text() if response_body.exists() else '',
                          'headers': selected_headers}
                if response.returncode:
                    result['transport_error'] = 'curl exit ' + str(response.returncode)
            except (OSError, subprocess.TimeoutExpired) as error:
                result = {'status': 0, 'body': '', 'headers': {}, 'transport_error': type(error).__name__}
        result.update(label=label, path=path, correlation_id=correlation,
                      latency_ms=round((time.monotonic() - start) * 1000, 2))
        with self.guard:
            self.responses.append(result)
            with (self.directory / 'responses.jsonl').open('a') as out:
                out.write(json.dumps(result) + '\n')
        return result

    def body(self, response, status):
        require(response['status'] == status,
                f"{response['label']}: expected HTTP {status}, got {response['status']}: {response['body'][:250]}")
        require(response['headers'].get('x-correlation-id') == response['correlation_id'], 'Correlation ID missing or changed')
        body = json.loads(response['body'])
        require(set(body) == {'success', 'data', 'error'}, 'Unexpected response envelope')
        return body

    def burst(self, count, operation):
        barrier = threading.Barrier(count + 1)
        def run(index):
            barrier.wait(timeout=30)
            return operation(index)
        with concurrent.futures.ThreadPoolExecutor(max_workers=count) as executor:
            futures = [executor.submit(run, i) for i in range(count)]
            barrier.wait(timeout=30)
            return [future.result() for future in futures]

    def sql(self, sql):
        if self.args.db == 'compose':
            command = ['docker', 'compose', 'exec', '-T', 'postgres', 'psql', '-X', '-U', 'wallet', '-d', 'wallet']
        else:
            require(os.environ.get('PGHOST') and os.environ.get('PGDATABASE') and os.environ.get('PGUSER'),
                    'Set PGHOST, PGDATABASE, PGUSER and use PGPASSWORD or a local password file')
            if shutil.which('psql'):
                command = ['psql', '-X', '-w']
            else:
                require(shutil.which('docker'), 'Install psql or Docker to use --db postgres')
                command = ['docker', 'run', '--rm', '-i']
                for name in ('PGHOST', 'PGPORT', 'PGDATABASE', 'PGUSER', 'PGPASSWORD', 'PGSSLMODE', 'PGCHANNELBINDING'):
                    if name in os.environ:
                        command += ['-e', name]
                command += ['postgres:16-alpine', 'psql', '-X', '-w']
        command += ['-A', '-t', '-v', 'ON_ERROR_STOP=1']
        result = subprocess.run(command, input=sql, text=True, capture_output=True, cwd=ROOT, timeout=90)
        require(result.returncode == 0, 'Database command failed; check connection settings (credentials are not logged)')
        return result.stdout.strip()

    def wallet(self, index):
        return self.state['wallets'][index]

    def balance(self, index):
        wallet = self.wallet(index)
        body = self.body(self.request('GET', self.state['api_prefix'] + '/wallets/' + wallet['id'], wallet['user']), 200)
        return body['data']['balance_paise']

    def balances(self):
        balances = [self.balance(i) for i in range(4)]
        require(all(type(b) is int and b >= 0 for b in balances), 'Negative or non-integer balance')
        return balances

    def transfer(self, source, destination, amount, key, label, prefix=None):
        return self.request('POST', (self.state['api_prefix'] if prefix is None else prefix) + '/transfers',
                            self.wallet(source)['user'], {'from': self.wallet(source)['id'], 'to': self.wallet(destination)['id'],
                                                        'amount_paise': amount, 'idempotency_key': key}, label)

    def prepare(self):
        print('1/5: 50 simultaneous get-or-create requests', flush=True)
        user = self.state['run_id'] + '-0'
        results = self.burst(50, lambda i: self.request('POST', self.state['api_prefix'] + '/wallets', user,
                                                      {'user_id': user}, 'provision-storm'))
        wallets = [self.body(r, 200)['data'] for r in results]
        require(len({w['id'] for w in wallets}) == 1, 'Provisioning returned multiple wallet IDs')
        require(all(w['balance_paise'] == 0 and w['user_id'] == user for w in wallets), 'Fresh wallet mismatch')
        self.state['wallets'] = [{'id': wallets[0]['id'], 'user': user}]
        for i in range(1, 4):
            user = self.state['run_id'] + '-' + str(i)
            wallet = self.body(self.request('POST', self.state['api_prefix'] + '/wallets', user,
                                            {'user_id': user}, 'provision'), 200)['data']
            require(wallet['balance_paise'] == 0, 'New wallet must start at zero')
            self.state['wallets'].append({'id': wallet['id'], 'user': user})
        self.validate_wallets()
        conditions = ' OR '.join(f"(id = '{w['id']}' AND user_id = '{w['user']}')" for w in self.state['wallets'])
        seed = f"""-- Fund only this run's four fresh wallets, atomically, or update none.
WITH candidates AS (
    SELECT id FROM wallets WHERE ({conditions}) AND balance_paise = 0 FOR UPDATE
), funded AS (
    UPDATE wallets SET balance_paise = {SEED}, updated_at = NOW()
    WHERE id IN (SELECT id FROM candidates) AND (SELECT count(*) FROM candidates) = 4
    RETURNING id
)
SELECT count(*) AS funded_wallets FROM funded;
"""
        (self.directory / 'seed.sql').write_text(seed)
        self.state['provisioning_passed'] = True
        self.save()
        return seed

    def validate_wallets(self):
        require(len(self.state['wallets']) == 4, 'Expected exactly four test wallets')
        for wallet in self.state['wallets']:
            require(re.fullmatch(r'[A-Za-z0-9_-]{1,64}', wallet['id']) is not None, 'Unsafe wallet ID')
            require(re.fullmatch(r'burst-[a-f0-9]{20}-[0-3]', wallet['user']) is not None, 'Not an isolated test user')

    def save(self):
        (self.directory / 'state.json').write_text(json.dumps(self.state, indent=2) + '\n')

    def exercise(self):
        self.validate_wallets()
        initial = self.balances()
        require(initial == [SEED] * 4, 'Funding not visible through API; check seed.sql and target database')
        self.state['exercise_started'] = True
        self.save()
        key = self.state['run_id'] + '-storm'
        print('2/5: 30 simultaneous identical transfers', flush=True)
        results = self.burst(30, lambda i: self.transfer(0, 1, 1000, key, 'idempotency-storm'))
        require(collections.Counter(r['status'] for r in results) == {201: 1, 200: 29}, 'Retry storm HTTP status mismatch')
        require(len({r['body'] for r in results}) == 1, 'Retry storm response bodies differ')
        original = self.body(next(r for r in results if r['status'] == 201), 201)
        require(original['success'] and original['data']['status'] == 'SUCCESS', 'Transfer did not succeed')
        for result in results:
            self.body(result, result['status'])
            if result['status'] == 200:
                require(result['headers'].get('idempotent-replay') == 'true', 'Replay header absent')
        expected = [SEED - 1000, SEED + 1000, SEED, SEED]
        require(self.balances() == expected, 'Transfer applied more than once')
        print('3/5: conflicting key and cross-route replay', flush=True)
        conflict = self.body(self.transfer(0, 1, 1001, key, 'key-conflict'), 409)
        require(conflict['error']['code'] == 'IDEMPOTENCY_CONFLICT', 'Incorrect conflict code')
        alternate = '' if self.state['api_prefix'] else '/api/v1'
        replay = self.transfer(0, 1, 1000, key, 'route-alias-replay', alternate)
        self.body(replay, 200)
        require(replay['body'] == results[0]['body'], 'Route aliases do not share idempotency')
        require(self.balances() == expected, 'Conflict or alias replay moved money')
        print('4/5: 200 simultaneous mixed transfers across four wallets, including overdrafts', flush=True)
        requests = []
        for i in range(200):
            source = (i // 2) % 4
            destination = (source + 1) % 4
            if i % 2:
                source, destination = destination, source
            requests.append((source, destination, 5_000_000 if i % 4 == 0 else 100, self.state['run_id'] + f'-mixed-{i}'))
        results = self.burst(200, lambda i: self.transfer(*requests[i], label='contention'))
        ids = {original['data']['id']}
        declined = None
        for request, response in zip(requests, results):
            source, destination, amount, request_key = request
            status = 422 if amount > SEED * 4 else 201
            body = self.body(response, status)
            transfer = body['data']
            require(transfer['id'] not in ids, 'Different keys reused one transfer record')
            ids.add(transfer['id'])
            require(transfer['amount_paise'] == amount and transfer['idempotency_key'] == request_key, 'Transfer body mismatch')
            if status == 201:
                require(body['success'] and transfer['status'] == 'SUCCESS', 'Incorrect success outcome')
                expected[source] -= amount
                expected[destination] += amount
            else:
                require(not body['success'] and transfer['status'] == 'DECLINED_INSUFFICIENT_FUNDS', 'Incorrect decline outcome')
                declined = (request, response)
        final = self.balances()
        require(final == expected and sum(final) == sum(initial), 'Conservation or per-wallet accounting failed')
        request, decline = declined
        replay = self.transfer(*request, label='decline-replay')
        self.body(replay, 200)
        require(replay['body'] == decline['body'], 'Declined replay changed outcome')
        fetched = self.request('GET', self.state['api_prefix'] + '/transfers/' + original['data']['id'], self.wallet(0)['user'])
        require(self.body(fetched, 200) == original, 'Persisted transfer read differs')
        print('5/5: database evidence and Prometheus counters', flush=True)
        ids_sql = ', '.join("'" + w['id'] + "'" for w in self.state['wallets'])
        checks = f"""SELECT count(*) AS transfer_rows, count(*) FILTER (WHERE status = 'SUCCESS') AS successful,
 count(*) FILTER (WHERE status = 'DECLINED_INSUFFICIENT_FUNDS') AS declined
 FROM transfers WHERE source_wallet_id IN ({ids_sql});
SELECT count(*) AS wallet_rows, sum(balance_paise) AS total_paise, min(balance_paise) AS minimum_paise
 FROM wallets WHERE id IN ({ids_sql});
"""
        (self.directory / 'verify.sql').write_text(checks)
        database_checked = self.args.db != 'manual'
        if database_checked:
            lines = self.sql(checks).splitlines()
            require(lines[0] == '201|151|50', 'Unexpected persisted transfer counts: ' + lines[0])
            require(lines[1] == f'4|4000000|{min(final)}', 'Unexpected persisted wallet balances')
            (self.directory / 'database.txt').write_text('\n'.join(lines) + '\n')
        metrics = self.request('GET', '/metrics', label='metrics')
        require(metrics['status'] == 200, 'Metrics unavailable')
        for name in ('http_server_requests_seconds_bucket', 'http_server_requests_seconds_count',
                     'wallet_transfers_successful_total', 'wallet_transfers_declined_insufficient_funds_total',
                     'wallet_transfers_idempotent_replays_total'):
            require(name in metrics['body'], 'Metric absent: ' + name)
        (self.directory / 'metrics.txt').write_text(metrics['body'])
        report = {'result': 'PASS', 'base_url': self.state['base_url'], 'run_id': self.state['run_id'],
                  'completed_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  'provisioning_concurrency': 50, 'idempotency_concurrency': 30, 'contention_concurrency': 200,
                  'initial_balances': initial, 'final_balances': final, 'distinct_transfer_ids': len(ids),
                  'successful_transfers': 151, 'declined_transfers': 50,
                  'database_checked': database_checked, 'requests': {}}
        for label in ('provision-storm', 'idempotency-storm', 'contention'):
            samples = [r for r in self.responses if r['label'] == label]
            if samples:
                latencies = sorted(r['latency_ms'] for r in samples)
                report['requests'][label] = {'count': len(samples), 'p99_ms': latencies[math.ceil(len(samples) * .99) - 1],
                                             'statuses': dict(collections.Counter(r['status'] for r in samples))}
        (self.directory / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
        self.state['complete'] = True
        self.save()
        print(f"PASS: all HTTP invariants; database SQL {'verified' if database_checked else 'requires running verify.sql'}. Evidence: {self.directory}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default=os.environ.get('BASE_URL', 'http://localhost:' + os.environ.get('WALLET_PORT', '8080')))
    parser.add_argument('--api-prefix', choices=('', '/api/v1'), default='/api/v1')
    parser.add_argument('--db', choices=('compose', 'postgres', 'manual'), default=None)
    parser.add_argument('--prepare', action='store_true', help='Create test wallets and funding SQL, then stop')
    parser.add_argument('--resume', type=Path, help='Continue a prepared run after funding its wallets')
    parser.add_argument('--output', type=Path, help='New evidence directory (must not already exist)')
    args = parser.parse_args()
    if args.resume:
        state = json.loads((args.resume / 'state.json').read_text())
        require(not state.get('exercise_started'), 'This run already started transfers. Start a fresh run instead.')
        require(state.get('provisioning_passed'), 'Provisioning did not finish')
    else:
        run_id = 'burst-' + uuid.uuid4().hex[:20]
        directory = (args.output or ROOT / 'evidence' / 'runs' / run_id).resolve()
        directory.mkdir(parents=True, exist_ok=False)
        state = {'run_id': run_id, 'base_url': args.base_url.rstrip('/'), 'api_prefix': args.api_prefix,
                 'directory': str(directory)}
    host = urllib.parse.urlsplit(state['base_url']).hostname
    args.db = args.db or ('compose' if host in ('localhost', '127.0.0.1') else 'manual')
    require(args.db != 'compose' or host in ('localhost', '127.0.0.1'), 'Refusing to seed local Docker for a remote API')
    runner = Runner(args, state)
    try:
        health = runner.request('GET', '/actuator/health/readiness', label='warmup', timeout=120)
        require(health['status'] == 200 and json.loads(health['body'])['status'] == 'UP', 'Readiness failed')
        if not args.resume:
            seed = runner.prepare()
            if args.prepare or args.db == 'manual':
                print(f"Run {runner.directory / 'seed.sql'} in the target database; expect funded_wallets = 4.")
                print(f"Then: ./scripts/burst_test.sh --db {args.db} --resume {runner.directory}")
                return
            require(runner.sql(seed) == '4', 'Funding did not match exactly four fresh wallets; check database target')
        runner.exercise()
    except Exception as error:
        (runner.directory / 'failure.json').write_text(json.dumps({'result': 'FAIL', 'reason': str(error)}, indent=2) + '\n')
        raise


if __name__ == '__main__':
    main()
