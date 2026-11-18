# 0.14.1 调描述时用的变体：从 0.14.0 测试机写出的描述（../taku-memory-0.14.0-2026-11-07/describe-asks.json）里取出条目、消息、Taku 的话和 actions，只换外面的写法。
"""Builds description variants from the device-written descriptions (asks-d.json) without touching their content:
the items, message, Taku's line and the exact actions JSON line are taken from each prompt; only the wording around them changes."""
import json, re, sys

def parts(a):
    p = a['prompt']; lines = p.split('\n')
    act = [l for l in lines if l.startswith('{"')][0]
    head = lines[1].rstrip('。')
    if a['kind'] == 'evening':
        items = [re.sub(r'^\d+\. ', '', l) for l in lines if re.match(r'^\d+\. ', l)]
        return dict(head=head, items=items, actions=act)
    i = lines.index([l for l in lines if l.startswith('先引用这条消息')][0])
    j = lines.index([l for l in lines if l.startswith('再用醒目的大字')][0])
    who = re.match(r'做一张 Taku 首页卡片：(.*) 发来一条新消息', lines[0]).group(1)
    label = re.search(r'标注「(.*)」，消息原样照抄', lines[i]).group(1)
    tag = [l for l in lines if l.startswith('在 Taku 要说的话下面加一个标签')]
    return dict(head=head, who=who, label=label, message='\n'.join(lines[i+1:j]), say=lines[j+1], tag=tag[0] if tag else '', actions=act)

RULES_V1 = ('结构：根元素是一个 Column，children 从上到下是抬头、正文、按钮那一行；每个元素都要挂在这棵树上。\n'
            '按钮那一行只放 actions 里的按钮，按它们在 JSON 里的顺序：最后一个是主按钮（primary），其余是次要按钮（secondary）。'
            '每个按钮元素在 type、props、children 之外再加一个 on 字段：{"press":{"action":"dispatch","params":{"actionId":"它在 actions 里的键"}}}。\n'
            'actions 原样使用下面这个 JSON 对象，一个字也不要改：\n%s\n'
            '这里没有图片和头像，不要用 MessageQuote 这类需要图片或头像的组件。summary 以不超过 20 个字的短标题开头，再用一句话概括卡片。只用这里给出的内容，不要补充任何信息。'
            '按 summary、actions、ui 的顺序输出一个完整的 JSON 对象；元素 id 用简短的英文，不写用不到的属性。')

PAGING_V1 = ('每件是一段文字，不加编号，最多显示两行。多于 3 件时分页：Pager 的 props 写 {"index":{"$bindState":"/ui/page"}}，每一页是一个放 3 件以内的 Column；'
             'Pager 下面放 Dots，props 写 {"count":页数,"index":{"$state":"/ui/page"}}；ui.state 写 {"ui":{"page":0}}。')

def v1(a):
    x = parts(a)
    if a['kind'] == 'evening':
        body = ('做一张 Taku 首页卡片：睡前提醒。内容是 Taku 记忆从用户自己的消息和记录里整理出的、明天之前要处理的事。\n%s。\n'
                '按顺序列出下面 %d 件事，每件原样照抄，不要改写、合并、拆分、省略或补充：\n%s\n%s'
                % (x['head'], len(x['items']), '\n'.join('· ' + t for t in x['items']), PAGING_V1))
    else:
        body = ('做一张 Taku 首页卡片：%s 发来一条新消息，Taku 记忆想起了和它有关的事。\n%s。\n'
                '先用 Inset 引用这条消息：上面一行小字写「%s」，下面原样照抄消息：\n%s\n'
                '再用醒目的大字写出 Taku 要说的话，原样照抄，这是卡片的重点：\n%s%s'
                % (x['who'], x['head'], x['label'], x['message'], x['say'], ('\n' + x['tag']) if x['tag'] else ''))
    return body + '\n\n' + RULES_V1 % x['actions']

def summary(a, x):
    if a['kind'] == 'evening':
        m = re.findall(r'「([^」]*)」', x['head']); title = m[1] if len(m) > 1 else ''
        n = len(x['items'])
        return (title + '：' if title else '') + x['items'][0] + ('（另有 %d 件）' % (n - 1) if n > 1 else '')
    return x['say']

def v2(a):
    x = parts(a); p = v1(a)
    old = 'summary 以不超过 20 个字的短标题开头，再用一句话概括卡片。'
    assert old in p
    return p.replace(old, 'summary 原样写这句：%s。' % summary(a, x).rstrip('。'))

def v3(a):
    x = parts(a); p = v2(a)
    old = '结构：根元素是一个 Column，children 从上到下是抬头、正文、按钮那一行；每个元素都要挂在这棵树上。'
    assert old in p
    new = ('结构：根元素是一个 Column，children 从上到下是抬头、正文、按钮那一行；分页时就是抬头、Pager、Dots、按钮那一行。每个元素都要挂在这棵树上。'
           if a['kind'] == 'evening' else '结构：根元素是一个 Column，children 从上到下是抬头、引用、Taku 要说的话、标签（有的话）、按钮那一行。每个元素都要挂在这棵树上。')
    p = p.replace(old, new)
    pretty = json.dumps(json.loads(x['actions']), ensure_ascii=False, indent=1)
    assert x['actions'] in p
    return p.replace('actions 原样使用下面这个 JSON 对象，一个字也不要改：\n' + x['actions'], 'actions 原样使用下面这个 JSON 对象，一个字也不要改，注意结尾的括号：\n' + pretty)

if __name__ == '__main__':
    src, out, name = sys.argv[1:4]
    fn = {'v1': v1, 'v2': v2, 'v3': v3}[name]
    asks = json.load(open(src)); res = []
    for a in asks:
        b = dict(a); b['prompt'] = fn(a); res.append(b)
    json.dump(res, open(out, 'w'), ensure_ascii=False, indent=1)
    print(res[0]['prompt']); print('---'); print(res[-1]['prompt'])
    print('lengths', sum(len(a['prompt']) for a in asks) // len(asks), '->', sum(len(a['prompt']) for a in res) // len(res))
