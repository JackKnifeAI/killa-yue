"""CLIP's byte-pair tokenizer (the one Stable Diffusion 1.5 uses), enough to turn a prompt into 77 ids."""
import gzip, re, sys, html

def load(path='/root/src/npu/bpe_simple_vocab_16e6.txt.gz'):
    merges = gzip.open(path).read().decode('utf-8').split('\n')[1:49152 - 256 - 2 + 1]
    merges = [tuple(m.split()) for m in merges]
    chars = [chr(b) for b in range(256)]
    # CLIP's byte table: printable ranges first, the rest remapped
    bs = list(range(ord('!'), ord('~') + 1)) + list(range(ord('\xa1'), ord('\xac') + 1)) + list(range(ord('\xae'), ord('\xff') + 1))
    cs = bs[:]; n = 0
    for b in range(256):
        if b not in bs: bs.append(b); cs.append(256 + n); n += 1
    byte = dict(zip(bs, [chr(c) for c in cs]))
    vocab = list(byte.values()); vocab = vocab + [v + '</w>' for v in vocab]
    for m in merges: vocab.append(''.join(m))
    vocab += ['<|startoftext|>', '<|endoftext|>']
    return dict(zip(vocab, range(len(vocab)))), dict(zip(merges, range(len(merges)))), byte

ENC, RANKS, BYTE = load()

def bpe(token):
    word = tuple(token[:-1]) + (token[-1] + '</w>',)
    while len(word) > 1:
        pairs = set(zip(word, word[1:]))
        best = min(pairs, key=lambda p: RANKS.get(p, float('inf')))
        if best not in RANKS: break
        a, b = best; out = []; i = 0
        while i < len(word):
            if i < len(word) - 1 and word[i] == a and word[i + 1] == b: out.append(a + b); i += 2
            else: out.append(word[i]); i += 1
        word = tuple(out)
    return word

def tokens(text):
    text = re.sub(r'\s+', ' ', html.unescape(text)).strip().lower()
    ids = [49406]
    for tok in re.findall(r"<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[^\W\d_]+|\d|[^\s\w]+|_+", text):
        tok = ''.join(BYTE[b] for b in tok.encode('utf-8'))
        ids += [ENC[p] for p in bpe(tok)]
    ids = ids[:76] + [49407]
    return ids + [49407] * (77 - len(ids))

if __name__ == '__main__':
    print(','.join(str(i) for i in tokens(sys.argv[1])))
