#!/usr/bin/env node
/* 合成の部品（mp4ReadVideo / mp4Build）の自動テスト。ffmpeg と ffprobe が必要。
 *   node tests/mux_node.js
 * 2.000秒ちょうどに白く光る無音の映像と、2.000秒にクリックが鳴る音声を合成して、
 * 長さ・音声トラック・映像と音のずれ・映像が1コマも変わっていないことを確かめる。 */
const fs=require('fs'),os=require('os'),path=require('path'),{execFileSync}=require('child_process');
const html=fs.readFileSync(path.join(__dirname,'../prototype/index.html'),'utf8');
const a=html.indexOf('/*MP4MUX-BEGIN*/'),b=html.indexOf('/*MP4MUX-END*/');if(a<0||b<0)throw new Error('MP4MUXの目印がありません');
const {mp4ReadVideo,mp4Build}=new Function(html.slice(a,b)+';return {mp4ReadVideo,mp4Build}')();
const T=fs.mkdtempSync(path.join(os.tmpdir(),'mux-'));const sh=(c,args,opt={})=>execFileSync(c,args,{maxBuffer:1<<28,...opt});
const FLASH="color=c=black:s=320x180:r=60:d=6,drawbox=x=0:y=0:w=iw:h=ih:color=white:t=fill:enable='eq(n\\,120)'";
const ff=(...x)=>sh('ffmpeg',['-v','error','-y',...x]);
function adts(buf){const fr=[];let o=0,rate=0,ch=0;const R=[96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000];
  while(o+7<=buf.length){if(buf[o]!==0xff||(buf[o+1]&0xf0)!==0xf0)break;const prot=buf[o+1]&1,len=((buf[o+3]&3)<<11)|(buf[o+4]<<3)|(buf[o+5]>>5);
    rate=R[(buf[o+2]>>2)&15];ch=((buf[o+2]&1)<<2)|(buf[o+3]>>6);fr.push(buf.subarray(o+(prot?7:9),o+len));o+=len}
  const fi=R.indexOf(rate);return {codec:'mp4a',rate,ch,frameDur:1024,frames:fr,asc:new Uint8Array([(2<<3)|(fi>>1),((fi&1)<<7)|(ch<<3)]),skip:1024}}
const probe=f=>JSON.parse(sh('ffprobe',['-v','error','-show_entries','format=duration:stream=codec_type,codec_name,nb_frames','-of','json',f]).toString());
const frameMd5=f=>sh('ffmpeg',['-v','error','-i',f,'-map','0:v','-fps_mode','passthrough','-f','framemd5','-']).toString().split('\n').filter(l=>l&&l[0]!=='#').map(l=>l.split(',').pop().trim()).join();
function flashAt(f){const o=sh('ffprobe',['-v','error','-f','lavfi',`movie=${f},signalstats`,'-show_entries','frame=pts_time:frame_tags=lavfi.signalstats.YAVG','-of','csv=p=0']).toString();
  for(const l of o.split('\n')){const [t,y]=l.split(',');if(parseFloat(y)>128)return parseFloat(t)}return NaN}
function clickAt(f){const pcm=sh('ffmpeg',['-v','error','-i',f,'-map','0:a','-ac','1','-ar','48000','-f','f32le','-']);const x=new Float32Array(pcm.buffer,pcm.byteOffset,pcm.byteLength>>2);
  for(let i=0;i<x.length;i++)if(Math.abs(x[i])>0.4)return i/48000;return NaN}
let fail=0;const ok=(name,cond,detail='')=>{console.log((cond?'  ok   ':'  FAIL ')+name+(detail?'  '+detail:''));if(!cond)fail++};
async function write(blob,f){fs.writeFileSync(f,Buffer.from(await blob.arrayBuffer()))}
(async()=>{
  // 入力を作る
  const wav=path.join(T,'click.wav'),n=48000*6,pcm=Buffer.alloc(44+n*4);pcm.write('RIFF',0);pcm.writeUInt32LE(36+n*4,4);pcm.write('WAVEfmt ',8);pcm.writeUInt32LE(16,16);pcm.writeUInt16LE(1,20);pcm.writeUInt16LE(2,22);pcm.writeUInt32LE(48000,24);pcm.writeUInt32LE(192000,28);pcm.writeUInt16LE(4,32);pcm.writeUInt16LE(16,34);pcm.write('data',36);pcm.writeUInt32LE(n*4,40);
  for(let i=0;i<n;i++){const v=Math.round(32767*((i>=96000&&i<96048)?0.9:0.02*Math.sin(2*Math.PI*220*i/48000)));pcm.writeInt16LE(v,44+i*4);pcm.writeInt16LE(v,46+i*4)}
  fs.writeFileSync(wav,pcm);const aac=path.join(T,'click.aac');ff('-i',wav,'-c:a','aac','-b:a','192k','-f','adts',aac);
  const X=['-c:v','libx264','-pix_fmt','yuv420p','-an'];
  const inputs={
    '細切れ形式':['-bf','0','-g','60','-movflags','frag_keyframe+empty_moov+default_base_moof'],
    '細切れ形式（Bフレームあり）':['-bf','2','-g','60','-movflags','frag_keyframe+empty_moov'],
    '細切れ形式（0.25秒ごと）':['-bf','0','-g','15','-frag_duration','250000','-movflags','empty_moov+default_base_moof'],
    '普通形式':['-bf','0','-g','60','-movflags','+faststart'],
    '普通形式（moovが後ろ）':['-bf','2','-g','60']};
  let i=0;for(const [name,opts] of Object.entries(inputs)){console.log(name);const vin=path.join(T,`v${i}.mp4`),out=path.join(T,`o${i}.mp4`);i++;
    ff('-f','lavfi','-i',FLASH,...X,...opts,vin);
    const vb=await fs.openAsBlob(vin),V=await mp4ReadVideo(vb),A=adts(new Uint8Array(fs.readFileSync(aac)));A.play=Math.ceil(V.sec*A.rate);
    const r=mp4Build(vb,V,A);await write(r.blob,out);const p=probe(out),vs=p.streams.find(s=>s.codec_type==='video'),as=p.streams.find(s=>s.codec_type==='audio');
    ok('コマ数',V.S.length===360&&+vs.nb_frames===360,`${V.S.length}コマ / キーフレーム${V.keyframes} / 断片${V.fragments}`);
    ok('長さ',Math.abs(parseFloat(p.format.duration)-V.sec)<0.03&&Math.abs(V.sec-6)<0.05,`${p.format.duration}s`);
    ok('音声トラックあり',!!as&&as.codec_name==='aac');
    ok('最後まで再生できる',(()=>{try{ff('-i',out,'-f','null','-');return true}catch(e){return false}})());
    ok('映像が1コマも変わっていない',frameMd5(vin)===frameMd5(out));
    const d=(clickAt(out)-flashAt(out))*1000;ok('映像と音のずれが1ms未満',Math.abs(d)<1,`${d.toFixed(2)}ms`);
    // 音だけ100ms前へずらして作り直す（音のタイミング調整と同じ処理）
    const vb2=await fs.openAsBlob(out),V2=await mp4ReadVideo(vb2),A2=adts(new Uint8Array(fs.readFileSync(aac)));A2.skip+=4800;A2.play=Math.ceil(V2.sec*A2.rate);
    const out2=path.join(T,`r${i}.mp4`);await write(mp4Build(vb2,V2,A2).blob,out2);
    const d2=(clickAt(out2)-flashAt(out2))*1000;ok('作り直し：音だけ100ms前へ',Math.abs(d2+100)<1&&frameMd5(vin)===frameMd5(out2),`${d2.toFixed(2)}ms`);
    // 音声なし（曲を選ばずに撮った場合）
    const out3=path.join(T,`s${i}.mp4`);await write(mp4Build(vb,V,null).blob,out3);const p3=probe(out3);
    ok('音声なしでも普通形式にできる',p3.streams.length===1&&Math.abs(parseFloat(p3.format.duration)-V.sec)<0.03&&frameMd5(vin)===frameMd5(out3))}
  console.log('音声だけのファイル');{const A=adts(new Uint8Array(fs.readFileSync(aac)));const out=path.join(T,'a.m4a');await write(mp4Build(null,null,A).blob,out);
    const c=clickAt(out);ok('クリックの位置',Math.abs(c-2)<0.001,`${c}s`)}
  fs.rmSync(T,{recursive:true,force:true});console.log(fail?`\n${fail}件の失敗`:'\nすべて成功');process.exit(fail?1:0)})().catch(e=>{console.error(e);process.exit(1)});
