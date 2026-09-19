# JS 音源接口（Android 版）

脚本需要在头部提供元信息：

```js
/**
 * @name 我的音源
 * @description 示例
 * @author xxx
 * @version 1.0.0
 * @homepage https://example.com
 */
```

初始化：

```js
lx.send('inited', {
  sources: {
    kw: { type: 'music', actions: ['musicUrl'], qualitys: ['128k','320k','flac','flac24bit'] }
  }
})
```

注册请求：

```js
lx.on('request', ({ source, action, info }) => {
  if (action !== 'musicUrl') throw new Error('unsupported')
  return fetchMusicUrl(source, info.type, info.musicInfo)
})
```

HTTP 请求：

```js
lx.request(url, { method: 'get', headers: {} }, (err, resp, body) => {
  // body 是自动尝试 JSON.parse 后的对象，否则为字符串
})
```

支持的加密/压缩接口：

- `lx.utils.crypto.md5`
- `lx.utils.crypto.sha1`
- `lx.utils.crypto.randomBytes`
- `lx.utils.crypto.aesEncrypt`
- `lx.utils.crypto.rsaEncrypt`
- `lx.utils.buffer.from`
- `lx.utils.buffer.bufToString`
- `lx.utils.zlib.inflate / deflate`

Android 版把用户脚本放在应用私有目录，不把脚本直接写入公共存储。
