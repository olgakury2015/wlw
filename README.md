# wlw

ZLM（Docker + 配置）
目录：
/opt/zlm/conf/config.ini # 必须是文件，不是目录
/opt/zlm/log/
config.ini 关键段：
[general]
listen_ip=0.0.0.0
[http]
port=88
[api]
secret=与 wlw application.yml 完全一致
[rtp_proxy]
port=30000
port_range=30000-30500
[rtsp]
port=554
启动示例：
docker run -d --name zlm --restart=always --network host \
-v /opt/zlm/conf/config.ini:/opt/media/conf/config.ini \
-v /opt/zlm/log:/opt/media/log \
zlmediakit/zlmediakit:master
wlw application.yml（服务器）
iot:
video:
decoder: ffmpeg
ffmpeg-path: /usr/bin/ffmpeg
gb28181-decoder: ffmpeg
gb28181-use-zlm: true
gb28181-zlm-fallback: false
gb28181-media-transport: tcp_passive
gb28181-rtsp-fallback: true # 可选，国标失败时用通道 RTSP
rtsp-transport: udp # 若仍用 RTSP 拉 ZLM；已用 HTTP-FLV 可忽略
vlc-path: # 留空，勿填 Windows 路径
zlm:
enabled: true
http-host: 127.0.0.1
http-port: 88
secret: （与 ZLM ini 一致）
config-ini: /opt/zlm/conf/config.ini
rtsp-port: 554
控制台 / 数据库配置


防火墙 / 安全组（腾讯云 + 系统）
端口	协议	用途	建议
5060	TCP + UDP	国标 SIP	尽量 仅摄像机公网 IP
30000–30500	TCP + UDP	国标媒体 / ZLM RTP	对摄像机来源或按需开放
9090 / 9091 等	TCP	wlw Web	按需
88、554	—	ZLM API / RTSP	一般 仅本机（127.0.0.1），不必对公网
SELinux（CentOS 7，若 ffmpeg 报 Permission denied）
sudo setsebool -P httpd_can_network_connect 1
sudo setsebool -P httpd_can_network_relay 1
验证命令（运维用）
# ZLM API
curl -s "http://127.0.0.1:88/index/api/getServerConfig?secret=你的secret"
# 是否有流
curl -s "http://127.0.0.1:88/index/api/getMediaList?secret=你的secret"
# ffmpeg 拉流（root / www 各测一次）
ffmpeg -i "http://127.0.0.1:88/rtp/设备_通道.live.flv" -t 3 -f null -
sudo -u www ffmpeg -i "http://127.0.0.1:88/rtp/设备_通道.live.flv" -t 3 -f null -
方法1：使用静态编译版本（推荐，最简单）
bash
# 1. 下载静态编译的 ffmpegcd /usr/local/binwget https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz# 2. 解压tar -xf ffmpeg-release-amd64-static.tar.xz# 3. 复制可执行文件到系统目录cp ffmpeg-*-static/ffmpeg /usr/local/bin/cp ffmpeg-*-static/ffprobe /usr/local/bin/# 4. 添加执行权限chmod +x /usr/local/bin/ffmpegchmod +x /usr/local/bin/ffprobe# 5. 创建软链接到系统路径ln -sf /usr/local/bin/ffmpeg /usr/bin/ffmpegln -sf /usr/local/bin/ffprobe /usr/bin/ffprobe# 6. 验证安装ffmpeg -version
方法2：使用 RPM Fusion 仓库安装
bash
# 1. 安装 EPEL 仓库yum install -y epel-release# 2. 安装 RPM Fusion 仓库yum localinstall --nogpgcheck https://download1.rpmfusion.org/free/el/rpmfusion-free-release-7.noarch.rpm# 3. 安装 ffmpegyum install -y ffmpeg ffmpeg-devel# 4. 验证安装ffmpeg -version
方法3：编译安装（最新版）
bash
# 1. 安装编译依赖yum install -y autoconf automake gcc gcc-c++ git libtool make nasm pkgconfig zlib-devel# 2. 下载源码cd /usr/local/srcwget https://ffmpeg.org/releases/ffmpeg-6.1.1.tar.xztar -xf ffmpeg-6.1.1.tar.xzcd ffmpeg-6.1.1# 3. 配置（注意：这里配置为静态编译，避免依赖问题）./configure --prefix=/usr/local --enable-static --disable-shared --enable-gpl --enable-nonfree# 4. 编译（使用多核加速）make -j$(nproc)# 5. 安装make install# 6. 创建软链接ln -sf /usr/local/bin/ffmpeg /usr/bin/ffmpeg# 7. 验证ffmpeg -version
验证安装
bash
# 查看版本ffmpeg -version | head -3# 查看路径which ffmpeg# 应该显示: /usr/bin/ffmpeg# 测试编解码器ffmpeg -encoders | grep -E "h264|libx264"ffmpeg -decoders | grep h264
解决 Java 程序找不到 ffmpeg 的问题
安装完成后，需要让 Java 程序使用系统的 ffmpeg：
方法A：修改配置文件
bash
# 找到 Java 程序的配置文件find /www/wwwroot/wlw -name "application*.yml" -o -name "application*.properties"# 添加以下配置vi /www/wwwroot/wlw/application.yml
yaml
iot:  video:    ffmpeg-path: /usr/bin/ffmpeg    rtsp-transport: udp
方法B：替换嵌入式 ffmpeg
bash
# 备份并替换 Java 程序自带的 ffmpegfind /www/wwwroot/wlw -name "ffmpeg" -path "*/javacpp/*" 2>/dev/null# 假设路径是（根据实际情况调整）：mv /home/www/.javacpp/cache/ffmpeg-6.1.1-1.5.10-linux-x86_64.jar/org/bytedeco/ffmpeg/linux-x86_64/ffmpeg \   /home/www/.javacpp/cache/ffmpeg-6.1.1-1.5.10-linux-x86_64.jar/org/bytedeco/ffmpeg/linux-x86_64/ffmpeg.bakln -s /usr/bin/ffmpeg \   /home/www/.javacpp/cache/ffmpeg-6.1.1-1.5.10-linux-x86_64.jar/org/bytedeco/ffmpeg/linux-x86_64/ffmpeg
方法C：设置环境变量
bash
# 编辑 /etc/profileecho 'export PATH=/usr/local/bin:$PATH' >> /etc/profilesource /etc/profile# 或者修改 Java 启动脚本vi /www/wwwroot/wlw/start.sh# 添加：export FFMPEG_PATH=/usr/bin/ffmpeg
测试 ffmpeg 是否能拉流
bash
# 测试从 ZLM 拉流（假设流存在）ffmpeg -rtsp_transport udp -i rtsp://127.0.0.1:554/rtp/your_stream_id -t 5 -f null -# 测试保存一帧图片ffmpeg -i rtsp://127.0.0.1:554/rtp/your_stream_id -frames:v 1 /tmp/test.jpg# 查看图片是否生成ls -la /tmp/test.jpg
如果 yum 安装失败，使用静态编译版本
静态编译版本没有依赖问题，是最可靠的选择：
bash
# 完整的一键安装脚本cat > /tmp/install_ffmpeg.sh << 'EOF'#!/bin/bashcd /usr/local/binwget https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xztar -xf ffmpeg-release-amd64-static.tar.xzcp ffmpeg-*-static/ffmpeg /usr/local/bin/cp ffmpeg-*-static/ffprobe /usr/local/bin/chmod +x /usr/local/bin/ffmpeg /usr/local/bin/ffprobeln -sf /usr/local/bin/ffmpeg /usr/bin/ffmpegln -sf /usr/local/bin/ffprobe /usr/bin/ffprobeffmpeg -version | head -3echo "FFmpeg installed successfully!"EOFchmod +x /tmp/install_ffmpeg.sh/tmp/install_ffmpeg.sh
