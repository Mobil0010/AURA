#!/usr/bin/env python3
"""Create our own deterministic 24-bit FLAC fixture using RFC 9639 verbatim frames."""
from pathlib import Path
import struct,hashlib

def sample(frame,channel):
    if frame==0:return -8388608 if channel==0 else 8388607
    return ((frame*104729+channel*1234567)&0xffffff)-8388608

def crc(data,width,polynomial):
    value=0
    for byte in data:
        value^=byte<<(width-8)
        for _ in range(8):value=((value<<1)^(polynomial if value&(1<<(width-1)) else 0))&((1<<width)-1)
    return value

frames=[]
for number in range(3):
    header=bytearray([0xff,0xf8,0xaa,0x1c,number]);header.append(crc(header,8,7))
    block=header
    for channel in range(2):
        block.append(2)
        for offset in range(1024):block.extend(sample(number*1024+offset,channel).to_bytes(3,'big',signed=True))
    block.extend(crc(block,16,0x8005).to_bytes(2,'big'));frames.append(block)
raw=b''.join(sample(frame,channel).to_bytes(3,'little',signed=True) for frame in range(3072) for channel in range(2))
info=struct.pack('>HH',1024,1024)+len(frames[0]).to_bytes(3,'big')*2+((48000<<44)|(1<<41)|(23<<36)|3072).to_bytes(8,'big')+hashlib.md5(raw).digest()
assert len(info)==34
out=Path(__file__).resolve().parent.parent/'app/src/androidTest/assets/pcm24.flac'
out.write_bytes(b'fLaC'+bytes([0x80,0,0,34])+info+b''.join(frames))
print('Generated synthetic fixture:',out.name,len(out.read_bytes()),'bytes')
