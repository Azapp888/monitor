// 高德地图 JS API 加载器
import AMapLoader from '@amap/amap-jsapi-loader'

let amapPromise = null

/**
 * 加载高德地图实例
 * @param {string} key 高德 Web 端 JS API Key
 * @returns {Promise<typeof AMap>}
 */
export function loadAMap() {
  if (amapPromise) return amapPromise

  const key = import.meta.env.VITE_AMAP_KEY
  const securityCode = import.meta.env.VITE_AMAP_SECURITY_CODE

  if (!key) {
    return Promise.reject(new Error('未配置高德地图 Key，请在 .env 中设置 VITE_AMAP_KEY'))
  }

  // 设置安全密钥（2021-12-02 后申请的 key 需要）
  if (securityCode) {
    window._AMapSecurityConfig = {
      securityJsCode: securityCode
    }
  }

  amapPromise = AMapLoader.load({
    key,
    version: '2.0',
    plugins: ['AMap.Scale', 'AMap.ToolBar', 'AMap.MarkerCluster']
  })
  return amapPromise
}

/** 是否已配置高德地图 key */
export function hasAmapKey() {
  return !!import.meta.env.VITE_AMAP_KEY
}
