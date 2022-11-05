import json, os, sys
import socket

# taken from https://github.com/antonydp/test-repository-for-yml
path = os.getcwd()
sys.path.insert(0, path)
if sys.version_info[0] >= 3:
    from lib import py3 as httplib2
else:
    from lib import py2 as httplib2


def http_Resp(lst_urls):
    rslt = {}
    for sito in lst_urls:
        try:
            s = httplib2.Http()
            code, resp = s.request(sito, body=None)
            if code.previous:
                rslt['code'] = code.previous['status']
                rslt['redirect'] = code.get('content-location', sito)
                rslt['status'] = code.status
                print("r1 http_Resp: %s %s %s %s" %
                      (code.status, code.reason, rslt['code'], rslt['redirect']))
            else:
                rslt['code'] = code.status
        except httplib2.ServerNotFoundError as msg:
            # both for lack of ADSL and for non-existent sites
            rslt['code'] = -2
        except socket.error as msg:
            # for unreachable sites without correct DNS
            # [Errno 111] Connection refused
            rslt['code'] = 111
        except:
            print()
            rslt['code'] = 'Connection error'
    return rslt


if __name__ == '__main__':
    fileJson = 'channels.json'

    with open(fileJson) as f:
        data = json.load(f)

    
    for k in data.keys():
        for chann, host in sorted(data[k].items()):
            if k == 'findhost':
                continue
            # to get an idea of the timing
            # useful only if you control all channels
            # for channels with error 522 about 40 seconds are lost ...
            print("check #### INIZIO #### channel - host :%s - %s " % (chann, host))

            rslt = http_Resp([host])

            # all right
            if rslt['code'] == 200:
                data[k][chann] = host
            # redirect
            elif str(rslt['code']).startswith('3'):
                # data[k][chann] = str(rslt['code']) +' - '+ rslt['redirect'][:-1]
                data[k][chann] = rslt['redirect']
            # cloudflare...
            elif rslt['code'] in [429, 503, 403]:
                from lib import proxytranslate
                import re

                print('Cloudflare riconosciuto')
                try:
                    page_data = proxytranslate.process_request_proxy(host).get('data', '')
                    data[k][chann] = re.search('<base href="([^"]+)', page_data).group(1)
                    rslt['code_new'] = 200
                except Exception as e:
                    import traceback
                    traceback.print_exc()
            # non-existent site
            elif rslt['code'] == -2:
                print('Host Sconosciuto - '+ str(rslt['code']) +' - '+ host)
            # site not reachable
            elif rslt['code'] == 111:
                print('Host non raggiungibile - '+ str(rslt['code']) +' - ' + host)
            else:
                # other types of errors
                print('Errore Sconosciuto - '+str(rslt['code']) +' - '+ host)

            print("check #### FINE #### rslt :%s  " % (rslt))
            if data[k][chann].endswith('/'):
                data[k][chann] = data[k][chann][:-1]

    # I write the updated file
    with open(fileJson, 'w') as f:
        json.dump(data, f, sort_keys=True, indent=4)
