// 乙打ちカメラ オフライン対応
// - 画面（HTML）：電波があれば最新版、弱い・ないときは端末に保存した版で起動
// - アイコン・フォントなど：端末の保存分を即使い、裏で更新
const CACHE = 'otsuuchicam-v9'; // 公開のたびに変える（変わると端末側が新しい版に切り替わる）
const CORE = ['./', './index.html', './icon.png', './icon-512.png', './manifest.webmanifest'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(CORE.map((u) => new Request(u, { cache: 'reload' })))).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

function timeout(ms) { return new Promise((resolve) => setTimeout(() => resolve(null), ms)); }

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;

  if (req.mode === 'navigate') {
    e.respondWith((async () => {
      const cache = await caches.open(CACHE);
      const network = fetch(req, { cache: 'no-store' }).then((res) => {
        if (res && res.ok) cache.put('./index.html', res.clone());
        return res;
      }).catch(() => null);
      // 電波が弱いときは2.5秒で見切りをつけて保存版を使う（裏で取得は続ける）
      const res = await Promise.race([network, timeout(2500)]);
      if (res) return res;
      const cached = await cache.match('./index.html');
      return cached || (await network) || new Response('オフラインです', { status: 503, headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
    })());
    return;
  }

  e.respondWith((async () => {
    const cache = await caches.open(CACHE);
    const hit = await cache.match(req);
    const network = fetch(req).then((res) => {
      if (res && (res.ok || res.type === 'opaque')) cache.put(req, res.clone());
      return res;
    }).catch(() => hit);
    return hit || network;
  })());
});
