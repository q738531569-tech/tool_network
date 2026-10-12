# 装维工具箱 · 远程AI控制 WebSocket 中转服务镜像
# 构建：docker build -t toolbox-remote .
# 运行：docker run -d --name toolbox-remote --restart always -p 8091:8091 toolbox-remote
FROM node:20-alpine

# 工作目录
WORKDIR /app

# 先拷贝依赖清单与入口（仅需这两个文件，node_modules 由 npm install 生成）
COPY package.json server.js ./

# 安装依赖（仅生产依赖；国内镜像加速）
RUN npm install --omit=dev --registry=https://registry.npmmirror.com

# 暴露端口（宝塔「安全」面板需放行）
EXPOSE 8091

# 启动服务
CMD ["node", "server.js"]
