"""Prepare a local Playwright state file. Never print its cookies or bootstrap password."""
import json
import argparse
from pathlib import Path
from platform_client import PlatformClient

parser=argparse.ArgumentParser()
parser.add_argument('--base',default='http://localhost:9900')
client=PlatformClient(parser.parse_args().base)
jar=next(handler.cookiejar for handler in client.opener.handlers if hasattr(handler,'cookiejar'))
cookies=[{'name':cookie.name,'value':cookie.value,'domain':'localhost','path':cookie.path,'expires':-1,'httpOnly':cookie.name=='JSESSIONID','secure':cookie.secure,'sameSite':'Lax'} for cookie in jar]
Path('target/platform-baseline').mkdir(parents=True, exist_ok=True)
Path('target/platform-baseline/.browser-auth.json').write_text(json.dumps({'cookies':cookies,'origins':[]}),encoding='utf-8')
print('Browser state prepared; credentials are not displayed.')
