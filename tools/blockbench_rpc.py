"""Task-local client for the user's running Blockbench MCP; no Blockbench files edited behind its back."""
import argparse, json, pathlib, sys, urllib.request

ENDPOINT = 'http://127.0.0.1:3000/bb-mcp'

class Blockbench:
    def __init__(self):
        self.session = None
        self.counter = 0
        self.rpc('initialize', dict(protocolVersion='2025-03-26', capabilities={},
                 clientInfo=dict(name='DungeonCore', version='1')))
        self.rpc('notifications/initialized', {}, notification=True)

    def rpc(self, method, params, notification=False):
        self.counter += 1
        payload = dict(jsonrpc='2.0', method=method, params=params)
        if not notification:
            payload['id'] = self.counter
        headers = {'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream'}
        if self.session:
            headers['Mcp-Session-Id'] = self.session
        request = urllib.request.Request(ENDPOINT, json.dumps(payload).encode(), headers)
        with urllib.request.urlopen(request, timeout=90) as response:
            self.session = response.headers.get('Mcp-Session-Id', self.session)
            raw = response.read().decode()
        if not raw:
            return {}
        if raw.startswith('event:') or raw.startswith('data:'):
            raw = next(line[5:].strip() for line in raw.splitlines() if line.startswith('data:'))
        result = json.loads(raw)
        if 'error' in result:
            raise RuntimeError(result['error'])
        return result.get('result', {})

    def call(self, name, arguments):
        result = self.rpc('tools/call', dict(name=name, arguments=arguments))
        if result.get('isError'):
            raise RuntimeError(result)
        return result

if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    parser = argparse.ArgumentParser()
    parser.add_argument('method')
    parser.add_argument('--input')
    parser.add_argument('--output')
    args = parser.parse_args()
    client = Blockbench()
    params = json.loads(pathlib.Path(args.input).read_text(encoding='utf-8')) if args.input else {}
    result = client.rpc(args.method, params)
    text = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        pathlib.Path(args.output).write_text(text, encoding='utf-8')
        print('Saved', args.output)
    else:
        print(text)
