/**
 * @name Lx 音源示例
 * @description 仅用于测试接口，不提供真实播放地址
 * @author LxMusicAndroid
 * @version 1.0.0
 * @homepage https://example.com
 */
lx.send('inited', {
  sources: {
    kw: { type: 'music', actions: ['musicUrl'], qualitys: ['128k','320k','flac','flac24bit'] }
  }
})

lx.on('request', ({ source, action, info }) => {
  throw new Error('请导入你自己的合法 JS 音源实现')
})
