#!/usr/bin/env python3
"""アプリ全体の自動テスト（Playwright）。録画 → 保存のあとに、長さ・音声トラックの有無・映像と音のずれを確かめる。
  python3 tests/e2e.py            （Chromium）
  BROWSER=webkit python3 tests/e2e.py
仕組み：サンプル曲の中身を「0.5秒ごとにクリックが鳴る音」に差し替え、デモ映像を「クリックの瞬間に白く光る画面」に
差し替えて撮影する。できた動画の「白くなる時刻」と「クリックの時刻」の差が、映像と音のずれ。
"""
import asyncio, base64, json, os, subprocess, sys, tempfile, array, statistics
from playwright.async_api import async_playwright
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
URL = 'file://' + os.path.join(ROOT, 'prototype', 'index.html')
BROWSER = os.environ.get('BROWSER', 'chromium')
TMP = tempfile.mkdtemp(prefix='e2e-')
fails = []; notes = []
def ok(name, cond, detail=''):
    print(('  ok   ' if cond else '  FAIL ') + name + ('  ' + str(detail) if detail != '' else ''), flush=True)
    if not cond: fails.append(name)
def run(*a): return subprocess.run(a, capture_output=True, check=True).stdout
def probe(f): return json.loads(run('ffprobe', '-v', 'error', '-show_entries', 'format=duration:stream=codec_type,codec_name,nb_frames', '-of', 'json', f))
def flashes(f):
    out = run('ffprobe', '-v', 'error', '-f', 'lavfi', f'movie={f},signalstats', '-show_entries', 'frame=pts_time:frame_tags=lavfi.signalstats.YAVG', '-of', 'csv=p=0').decode()
    res = []; prev = False
    for l in out.splitlines():
        p = l.split(',')
        if len(p) < 2 or not p[1]: continue
        on = float(p[1]) > 128
        if on and not prev: res.append(float(p[0]))
        prev = on
    return res
def clicks(f):
    pcm = run('ffmpeg', '-v', 'error', '-i', f, '-map', '0:a', '-ac', '1', '-ar', '48000', '-f', 'f32le', '-')
    x = array.array('f'); x.frombytes(pcm[:len(pcm) // 4 * 4]); res = []; i = 0; n = len(x)
    while i < n:
        if abs(x[i]) > 0.4: res.append(i / 48000); i += 9600
        else: i += 1
    return res
def offset_ms(f):
    """音が映像より何ms遅いか。白くなった時刻ごとに、いちばん近いクリックとの差を取る。
    戻り値：(動画の頭でのずれ, 頭から終わりまでに広がったずれ, 白の回数, クリックの回数)
    ※ヘッドレスのChromiumは録画した映像の時間が実際より数%長くなる（映像だけが少しずつ遅れていく）ので、
      方式の確認には「頭でのずれ」を使い、広がりは参考として表示する。"""
    fl = flashes(f); cl = clicks(f); d = [min((c - x for c in cl), key=abs) * 1000 for x in fl if cl]
    return (d[0] if d else None), (d[-1] - d[0] if d else None), len(fl), len(cl)

SETUP = r"""async()=>{
  const tr=S.tracks.find(t=>t.isSample);await ensureBuffer(tr);const old=S.buffers.get(tr.id),SR=old.sampleRate,b=ac().createBuffer(2,old.length,SR);
  for(let ch=0;ch<2;ch++){const d=b.getChannelData(ch);for(let i=0;i<d.length;i++)d[i]=0.02*Math.sin(2*Math.PI*220*i/SR);for(let k=0;k*SR/2+48<d.length;k++)for(let i=0;i<48;i++)d[Math.round(k*SR/2)+i]=0.9}
  S.buffers.set(tr.id,b);RB.clear();
  // クリップ：23.25秒〜27.25秒（クリックは 23.5, 24.0, … に入る）。フェードなし
  const c=tr.clips[0];c.start=23.25;c.end=27.25;c.fadeIn=0;c.fadeOut=0;c.preroll=0;setActiveClip(tr.id,c.id);
  // 映像：いま鳴っている曲の位置がクリックの直後50msなら白、それ以外は黒
  renderScene=function(){const r=S.rec;let w=false;if(r&&r.music){const p=AC.currentTime-r.musicT0+r.musicFrom,f=p*2-Math.floor(p*2);w=f<0.1}
    dc.globalAlpha=1;dc.globalCompositeOperation='source-over';dc.fillStyle=w?'#fff':'#000';dc.fillRect(0,0,demoCv.width,demoCv.height)};
  ST.countdown=0;ST.autoReview=false;ST.continuous=false;ST.blur=0;ST.syncMs=0;const enc=await audioEncSupport();return {mode:VS.mode,rec:typeof MediaRecorder,enc,newOk:!!enc&&typeof MediaRecorder!=='undefined'&&/mp4/.test(pickMime(true)),mime:typeof MediaRecorder!=='undefined'?pickMime(false):''}}"""
GET = r"""async(i)=>{const t=S.takes[i];const u=new Uint8Array(await t.blob.arrayBuffer());let b='';for(let k=0;k<u.length;k+=32768)b+=String.fromCharCode(...u.subarray(k,k+32768));
  return {b64:btoa(b),dur:t.dur,method:t.method,info:t.lenInfo,sync:t.sync,mime:t.mime,title:t.title}}"""

async def record(pg, secs=None):
    n = await pg.evaluate('S.takes.length')
    await pg.click('#recBtn')
    if secs:
        await pg.wait_for_timeout(int(secs * 1000)); await pg.click('#recBtn')
    await pg.wait_for_function(f'S.takes.length>{n}&&!S.rec', timeout=30000)
    await pg.wait_for_timeout(300)
async def take(pg, name, i=0):
    r = await pg.evaluate(GET, i); f = os.path.join(TMP, name + ('.mp4' if 'mp4' in r['mime'] else '.webm'))
    open(f, 'wb').write(base64.b64decode(r.pop('b64'))); return f, r

async def main():
    async with async_playwright() as p:
        bt = getattr(p, BROWSER)
        br = await bt.launch(args=['--autoplay-policy=no-user-gesture-required'] if BROWSER == 'chromium' else [])
        pg = await br.new_page(viewport={'width': 390, 'height': 844}); errs = []
        pg.on('pageerror', lambda e: errs.append(str(e)))
        await pg.goto(URL); await pg.wait_for_timeout(800)
        print(f'[{BROWSER}] 起動'); ver = await pg.evaluate('APP_VERSION'); ok('アプリが開く', bool(ver), ver)
        await pg.click('#wSample'); await pg.wait_for_function('S.tracks.some(t=>t.isSample)&&!!getActiveClip()', timeout=20000); await pg.wait_for_timeout(600)
        await pg.click('.tab[data-tab=camera]'); await pg.wait_for_timeout(500)
        env = await pg.evaluate(SETUP); print('  環境', env)
        can_rec = env['rec'] != 'undefined'
        if not can_rec:
            notes.append(f'{BROWSER}: このブラウザには録画機能（MediaRecorder）が無いため、録画のテストは省略'); print('  省略：録画機能なし')
        else:
            # ---- 新方式（音声の圧縮が使える場合） ----
            print('録画（初期設定）：クリップの終わりで自動停止')
            await record(pg); f, r = await take(pg, 'new'); print('  ', r['info'].replace('\n', '\n   '))
            pr = probe(f); vs = [s for s in pr['streams'] if s['codec_type'] == 'video']; au = [s for s in pr['streams'] if s['codec_type'] == 'audio']
            expect_new = bool(env['newOk'])
            ok('方式', r['method'] == ('new' if expect_new else 'old'), r['method'])
            ok('長さがクリップ（4秒）とほぼ同じ', abs(float(pr['format']['duration']) - 4.0) < 0.5 and abs(r['dur'] - float(pr['format']['duration'])) < 0.1, pr['format']['duration'])
            ok('映像トラック1本・音声トラック1本', len(vs) == 1 and len(au) == 1, [s['codec_name'] for s in pr['streams']])
            off, drift, nf, nc = offset_ms(f)
            ok('動画の頭で、映像と音のずれが60ms以内', off is not None and abs(off) < 60 and nf >= 6 and nc >= 6, f'{off:.1f}ms（白{nf}回・クリック{nc}回・終わりまでの広がり{drift:.0f}ms）' if off is not None else '測れない')
            base = off or 0
            if expect_new:
                ok('普通形式で保存されている', 'moof' not in open(f, 'rb').read()[:200000].decode('latin1'))
                print('音のタイミング調整：+100ms')
                await pg.click('.tab[data-tab=library]'); await pg.wait_for_timeout(300); await pg.click('.take'); await pg.wait_for_timeout(500)
                ok('「音ズレを直す」が出る', await pg.is_visible('#plAdj'))
                await pg.click('#plAdj'); await pg.wait_for_selector('#adjR')
                await pg.evaluate("()=>{const r=document.querySelector('#adjR');r.value=100;r.dispatchEvent(new Event('input'));r.dispatchEvent(new Event('change'))}")
                await pg.wait_for_timeout(1500); await pg.click('#adjOk'); await pg.wait_for_function("S.takes[0].sync.adjMs===100", timeout=15000); await pg.wait_for_timeout(500)
                f2, r2 = await take(pg, 'adj'); off2, _, _, _ = offset_ms(f2)
                ok('音だけ100ms遅くなる', off2 is not None and abs((off2 - base) - 100) < 3, f'{off2 - base:.1f}ms')
                ok('映像は変わらない', run('ffmpeg', '-v', 'error', '-i', f, '-map', '0:v', '-c', 'copy', '-f', 'md5', '-') == run('ffmpeg', '-v', 'error', '-i', f2, '-map', '0:v', '-c', 'copy', '-f', 'md5', '-'))
                ok('未保存の印に戻る', await pg.evaluate('S.takes[0].saved!==true'))
                # 端末に保存し直したものを読み込み直しても再生できるか
                await pg.reload(); await pg.wait_for_timeout(1200)
                d = await pg.evaluate("async()=>{const t=S.takes[0];const v=document.createElement('video');v.muted=true;v.src=URL.createObjectURL(t.blob);await new Promise((a,b)=>{v.onloadedmetadata=a;v.onerror=()=>b(new Error('再生できない'));setTimeout(()=>b(new Error('時間切れ')),8000)});return v.duration}")
                ok('開き直しても再生できる', abs(d - r2['dur']) < 0.2, f'{d:.2f}s')
                ok('調整後も動画の数は変わらない', await pg.evaluate('S.takes.length') == 1)
                ok('詳細に調整の所要時間', '音の調整+100ms' in (await pg.evaluate('S.takes[0].lenInfo')))
                # 調整の保存中にアプリを閉じた場合（新しい記録が書きかけ）→ 元の動画が残る
                await pg.evaluate("async()=>{const t=S.takes[0];await DB.put('takes',{...t,id:'half1',blob:new Blob([t.blob.slice(0,1000)],{type:'video/mp4'}),replacing:t.id,pending:true})}")
                await pg.reload(); await pg.wait_for_timeout(1200)
                ok('書きかけで閉じても元の動画が残る', await pg.evaluate("S.takes.length===1&&S.takes[0].id!=='half1'&&S.takes[0].blob.size>100000"))
                # 新しい記録は書き終わったが、古い記録を消す前に閉じた場合 → 新しい方だけ残る
                old_id = await pg.evaluate("async()=>{const t=S.takes[0];await DB.put('takes',{...t,id:'done1',replacing:t.id,pending:false});return t.id}")
                await pg.reload(); await pg.wait_for_timeout(1200)
                ok('書き終わりで閉じたら新しい方だけ残る', await pg.evaluate("S.takes.length===1&&S.takes[0].id==='done1'&&!S.takes[0].replacing"))
                await pg.click('.tab[data-tab=camera]'); await pg.wait_for_timeout(400); await pg.evaluate(SETUP)
                print('音ズレ補正の設定：-80ms、途中で手動停止、プリロール1秒')
                await pg.evaluate("()=>{ST.syncMs=-80;const tr=S.tracks.find(t=>t.isSample);tr.clips[0].preroll=1}")
                await record(pg, 3.2); f3, r3 = await take(pg, 'sync'); off3, _, nf3, _ = offset_ms(f3); pr3 = probe(f3)
                ok('音が80ms早くなる', off3 is not None and abs((off3 - base) + 80) < 40, f'{off3 - base:.1f}ms')
                ok('手動停止の長さ', 1.5 < float(pr3['format']['duration']) < 3.0, pr3['format']['duration'])
                await pg.evaluate("()=>{ST.syncMs=0;S.tracks.find(t=>t.isSample).clips[0].preroll=0}")
                print('曲なしで録画')
                await pg.evaluate("setActiveClip(null)"); await record(pg, 2); f4, r4 = await take(pg, 'nomusic'); pr4 = probe(f4)
                ok('音声なし・普通形式', len(pr4['streams']) == 1 and r4['method'] == 'new' and abs(float(pr4['format']['duration']) - r4['dur']) < 0.1, pr4['format']['duration'])
                await pg.evaluate(SETUP)
                # ---- 従来方式に戻せる ----
                print('録画方式「従来」')
                await pg.evaluate("ST.recMethod='old'"); await record(pg); f5, r5 = await take(pg, 'old'); pr5 = probe(f5)
                ok('従来方式で音入り', r5['method'] == 'old' and len(pr5['streams']) == 2 and abs(float(pr5['format']['duration']) - 4.0) < 0.6, pr5['format']['duration'])
                off5, dr5, _, _ = offset_ms(f5); print(f'   （参考）従来方式：頭でのずれ {off5:.1f}ms・広がり {dr5:.0f}ms' if off5 is not None else '')
                await pg.click('.tab[data-tab=library]'); await pg.wait_for_timeout(300); await pg.click('.take'); await pg.wait_for_timeout(400)
                ok('従来方式のテイクには「音ズレを直す」を出さない', not await pg.is_visible('#plAdj'))
                await pg.click('#plClose')
        # ---- 画面まわり ----
        await pg.click('.tab[data-tab=library]'); await pg.wait_for_timeout(300)
        ok('ライブラリに乙打ちドットコムへのリンク', await pg.get_attribute('.siteLink', 'href') == 'https://www.otsuuchi.com/')
        await pg.click('.tab[data-tab=settings]'); await pg.wait_for_timeout(300); body = await pg.inner_text('#settingsBody')
        ok('設定：解像度3種・録画方式あり・調査用の音質切り替えなし', all(x in body for x in ['720p', '1080p', '4K', '録画方式']) and '音声エンジンの音質' not in body)
        ok('解像度の初期値は1080p', await pg.evaluate("ST.res") == '1080')
        ok('画面のエラーなし', not errs, errs[:3])
        await br.close()
    for n in notes: print('::notice title=' + BROWSER + '::' + n)
    if fails: print('::error title=' + BROWSER + '::失敗: ' + ' / '.join(fails))
    print('\n' + (f'{len(fails)}件の失敗' if fails else 'すべて成功')); sys.exit(1 if fails else 0)
asyncio.run(main())
