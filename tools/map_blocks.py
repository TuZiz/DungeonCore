from pathlib import Path
import nbtlib,struct,zlib,io,functools
root=Path("C:/Users/32633/Desktop/悠梦星辰Minecraft26.1/.minecraft/versions/悠梦星辰Minecraft26.1/saves/[1.20+] The Lost Altar")
@functools.lru_cache(None)
def chunk(cx,cz):
 f=root/f"dimensions/minecraft/overworld/region/r.{cx//32}.{cz//32}.mca"
 if not f.exists():return {}
 b=f.read_bytes();i=((cx%32)+(cz%32)*32)*4;o=int.from_bytes(b[i:i+3],"big")*4096
 if not o:return {}
 n=int.from_bytes(b[o:o+4],"big");c=nbtlib.File.parse(io.BytesIO(zlib.decompress(b[o+5:o+4+n])))
 return {int(s["Y"]):s["block_states"] for s in c["sections"] if "block_states" in s}
def block(x,y,z):
 s=chunk(x//16,z//16).get(y//16)
 if s is None:return "air"
 p=s["palette"];b=max(4,(len(p)-1).bit_length());v=0
 if "data" in s:
  i=(y%16)*256+(z%16)*16+x%16;k=64//b;v=(int(s["data"][i//k])&((1<<64)-1))>>((i%k)*b)&((1<<b)-1)
 return str(p[v]["Name"]).removeprefix("minecraft:")
if __name__=="__main__":
 for x,z in [(0,0),(0,8),(0,16),(0,24),(-16,0),(16,0),(32,0),(0,32),(-32,0),(0,-16),(0,-32),(32,32),(-32,32)]:
  safe=[y for y in range(25,100) if block(x,y,z)=="air" and block(x,y+1,z)=="air" and block(x,y-1,z) not in ("air","water","lava","grass","tall_grass","short_grass")]
  print(x,z,[(y,block(x,y-1,z)) for y in safe])
