/**
 * OpenStreetMap + Leaflet：首页设备分布、设备详情单点。
 * 支持 Wikimedia 中文地名、天地图（可选 tk）、瓦片失败自动重试。
 */
(function (global) {
    'use strict';

    var ONLINE = '#c45d3a';
    var OFFLINE = '#94a3b8';

    var BUILTIN_FALLBACKS = [
        'https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png',
        'https://tile.openstreetmap.org/{z}/{x}/{y}.png'
    ];

    var DEFAULT_PRIMARY = 'https://maps.wikimedia.org/osm-intl/{z}/{x}/{y}.png';

    function cfg() {
        return global.WLW_MAP_OSM || {};
    }

    function esc(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/"/g, '&quot;');
    }

    /** 档案坐标 → 当前底图坐标系（天地图为 GCJ-02，其余为 WGS84） */
    function pinToMapCoords(pin) {
        if (!pin || typeof pin.lat !== 'number' || typeof pin.lng !== 'number' || !global.WlwCoord) {
            return null;
        }
        var wgs = global.WlwCoord.pinToWgs84(pin);
        if (!wgs) {
            return null;
        }
        if (cfg().useGcjMap) {
            var gcj = global.WlwCoord.wgs84ToGcj02(wgs.lng, wgs.lat);
            return { lat: gcj.lat, lng: gcj.lng };
        }
        return { lat: wgs.lat, lng: wgs.lng };
    }

    function popupHtml(p) {
        var name = p.name || p.deviceSn || '设备';
        var sn = p.deviceSn || '';
        var addr = p.address || '';
        var isOn = (p.status === 'ONLINE');
        var addrLine = addr ? '<div style="margin-top:6px;font-size:12px;color:#64748b">' + esc(addr) + '</div>' : '';
        return '<div style="min-width:180px;font-size:13px;color:#1e1a16"><strong>' + esc(name) + '</strong><br/>'
            + '<span style="color:#64748b;font-size:12px">' + esc(sn) + '</span>' + addrLine
            + '<div style="margin-top:8px;font-size:12px;color:' + (isOn ? '#c45d3a' : '#64748b') + '">'
            + (isOn ? '在线' : '离线') + '</div></div>';
    }

    function dotIcon(color) {
        return L.divIcon({
            className: 'wlw-map-dot',
            html: '<div style="width:14px;height:14px;border-radius:50%;background:' + color
                + ';border:2px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.15)"></div>',
            iconSize: [14, 14],
            iconAnchor: [7, 7]
        });
    }

    function tileLayerOptions(url, attribution) {
        var opt = {
            maxZoom: 19,
            attribution: attribution || '&copy; OpenStreetMap',
            crossOrigin: true,
            updateWhenIdle: true,
            updateWhenZooming: true,
            keepBuffer: 4
        };
        if (url.indexOf('{s}') >= 0) {
            if (url.indexOf('cartocdn.com') >= 0) {
                opt.subdomains = 'abcd';
            } else if (url.indexOf('tianditu.gov.cn') >= 0) {
                opt.subdomains = ['0', '1', '2', '3', '4', '5', '6', '7'];
            } else {
                opt.subdomains = 'abc';
            }
        }
        if (url.indexOf('wikimedia.org') >= 0) {
            opt.maxZoom = 18;
        }
        if (url.indexOf('tianditu.gov.cn') >= 0) {
            opt.maxZoom = 18;
        }
        return opt;
    }

    /** 单块瓦片加载失败时重试，减少灰色空格 */
    function wireTileRetry(layer, maxRetry) {
        var limit = maxRetry == null ? 3 : maxRetry;
        layer.on('tileerror', function (ev) {
            var tile = ev.tile;
            if (!tile || !tile._wlwRetry) {
                if (tile) {
                    tile._wlwRetry = 0;
                }
            }
            if (!tile || tile._wlwRetry >= limit) {
                return;
            }
            tile._wlwRetry++;
            var coords = ev.coords;
            setTimeout(function () {
                try {
                    tile.src = layer.getTileUrl(coords);
                } catch (e) { /* ignore */ }
            }, 400 * tile._wlwRetry);
        });
    }

    function collectTileSources() {
        var c = cfg();
        var out = [];
        var primary = c.tileUrl || DEFAULT_PRIMARY;
        out.push({ url: primary, attribution: c.attribution });
        var extras = c.tileFallbackUrls;
        if (!extras || !extras.length) {
            extras = BUILTIN_FALLBACKS;
        }
        for (var i = 0; i < extras.length; i++) {
            if (extras[i] && extras[i] !== primary) {
                out.push({ url: extras[i], attribution: c.attribution });
            }
        }
        return out;
    }

    function addTiandituLayers(map, tk) {
        var attr = (cfg().attribution || '')
            + ' &copy; <a href="https://www.tianditu.gov.cn" target="_blank" rel="noopener">天地图</a>';
        var subs = ['0', '1', '2', '3', '4', '5', '6', '7'];
        var base = L.tileLayer(
            'https://t{s}.tianditu.gov.cn/DataServer?T=vec_w&x={x}&y={y}&l={z}&tk=' + encodeURIComponent(tk),
            tileLayerOptions('https://t{s}.tianditu.gov.cn/', attr)
        );
        var label = L.tileLayer(
            'https://t{s}.tianditu.gov.cn/DataServer?T=cva_w&x={x}&y={y}&l={z}&tk=' + encodeURIComponent(tk),
            tileLayerOptions('https://t{s}.tianditu.gov.cn/', attr)
        );
        wireTileRetry(base);
        wireTileRetry(label);
        base.addTo(map);
        label.addTo(map);
        return base;
    }

    function addOsmTileLayers(map) {
        var sources = collectTileSources();
        var primary = sources[0];
        var layer = L.tileLayer(primary.url, tileLayerOptions(primary.url, primary.attribution));
        wireTileRetry(layer);
        layer.addTo(map);

        var failStreak = 0;
        var okStreak = 0;
        layer.on('tileerror', function () {
            failStreak++;
            okStreak = 0;
        });
        layer.on('tileload', function () {
            okStreak++;
            if (okStreak > 4) {
                failStreak = 0;
            }
        });

        if (sources.length > 1) {
            setTimeout(function () {
                if (failStreak >= 12 && okStreak < 2) {
                    map.removeLayer(layer);
                    var fb = sources[1];
                    var fbLayer = L.tileLayer(fb.url, tileLayerOptions(fb.url, fb.attribution));
                    wireTileRetry(fbLayer);
                    fbLayer.addTo(map);
                }
            }, 8000);
        }
        return layer;
    }

    function addBaseTiles(map) {
        var tk = (cfg().tiandituTk || '').trim();
        if (tk) {
            return addTiandituLayers(map, tk);
        }
        return addOsmTileLayers(map);
    }

    function refreshMapSize(map) {
        if (!map || typeof map.invalidateSize !== 'function') {
            return;
        }
        setTimeout(function () {
            map.invalidateSize();
        }, 80);
        setTimeout(function () {
            map.invalidateSize();
        }, 400);
    }

    function createBaseMap(containerId) {
        var map = L.map(containerId, { zoomControl: true }).setView([32, 105], 4);
        addBaseTiles(map);
        refreshMapSize(map);
        return map;
    }

    function homeMapInit() {
        if (typeof L === 'undefined' || !global.WlwCoord) {
            return;
        }
        var el = document.getElementById('wlw-map-pins');
        var pins = [];
        try {
            pins = el && el.textContent ? JSON.parse(el.textContent) : [];
        } catch (e) {
            pins = [];
        }
        var map = createBaseMap('homeMap');
        var markers = [];
        for (var i = 0; i < pins.length; i++) {
            var p = pins[i];
            var mc = pinToMapCoords(p);
            if (!mc) {
                continue;
            }
            var isOn = (p.status === 'ONLINE');
            var col = isOn ? ONLINE : OFFLINE;
            var m = L.marker([mc.lat, mc.lng], { icon: dotIcon(col) }).addTo(map);
            m.bindPopup(popupHtml(p));
            markers.push(m);
        }
        if (markers.length === 0) {
            map.setView([32, 105], 4);
        } else if (markers.length === 1) {
            map.setView(markers[0].getLatLng(), 10);
        } else {
            map.fitBounds(L.featureGroup(markers).getBounds().pad(0.12));
        }
        refreshMapSize(map);
    }

    function detailMapInit() {
        if (typeof L === 'undefined' || !global.WlwCoord) {
            return;
        }
        var el = document.getElementById('device-detail-map-pin');
        var pin = {};
        try {
            pin = el && el.textContent ? JSON.parse(el.textContent) : {};
        } catch (e) {
            pin = {};
        }
        if (!document.getElementById('deviceDetailMap')) {
            return;
        }
        var mc = pinToMapCoords(pin);
        if (!mc) {
            return;
        }
        var map = createBaseMap('deviceDetailMap');
        var title = pin.title || pin.deviceSn || '设备';
        var sn = pin.deviceSn || '';
        var html = '<div style="min-width:160px;font-size:13px;color:#1e1a16"><strong>' + esc(title) + '</strong>'
            + (sn ? '<br/><span style="color:#64748b;font-size:12px">' + esc(sn) + '</span>' : '') + '</div>';
        var m = L.marker([mc.lat, mc.lng], {
            icon: L.divIcon({
                className: 'wlw-map-dot',
                html: '<div style="width:16px;height:16px;border-radius:50%;background:#c45d3a;border:2px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.2)"></div>',
                iconSize: [16, 16],
                iconAnchor: [8, 8]
            })
        }).addTo(map);
        m.bindPopup(html).openPopup();
        map.setView([mc.lat, mc.lng], 15);
        refreshMapSize(map);
    }

    global.WlwMapOsm = {
        homeMapInit: homeMapInit,
        detailMapInit: detailMapInit,
        createBaseMap: createBaseMap,
        pinToMapCoords: pinToMapCoords
    };
})(typeof window !== 'undefined' ? window : this);
