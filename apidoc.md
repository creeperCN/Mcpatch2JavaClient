# 阿里云ESA A方案鉴权URL生成器 API 文档

## 概述

本API用于生成符合阿里云边缘安全加速(ESA) A方案规范的鉴权密钥(auth_key)，用于保护CDN资源访问安全。

**鉴权算法**：MD5(Path-timestamp-rand-uid-密钥)

---

## 基础信息

- **协议**：HTTPS
- **基础URL**：`https://auth-api.mxzysoa.com`
- **端口**：443 (默认)

---

## API端点

### 生成鉴权密钥

#### 端点
`GET /generate-auth-url`
`POST /generate-auth-url`

#### 请求参数

| 参数名 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| filePath | string | 是 | - | 资源文件路径，需以 `/` 开头，例如：`/index.json` |
| expireTime | integer | 否 | 3600 | 鉴权URL有效时长，单位：秒 |
| uid | string | 否 | 0 | 用户ID，根据业务需求设置 |
| rawKey | boolean | 否 | true (GET) / false (POST) | 是否直接返回auth_key字符串，而不返回JSON格式 |

#### 响应格式

##### 1. rawKey=true (默认GET请求)

**Content-Type**: text/plain

直接返回auth_key字符串，格式：`{timestamp}-{rand}-{uid}-{md5hash}`

**示例响应**：
```
1776791568-dH1VMcHAcH7XgXh9nlEQXIkNVohmD5iv-0-c670c5f30c9650ac20d70f621cb82df9
```

##### 2. rawKey=false (默认POST请求)

**Content-Type**: application/json

返回包含完整信息的JSON对象。

**示例响应**：
```json
{
    "authKey": "1776791568-dH1VMcHAcH7XgXh9nlEQXIkNVohmD5iv-0-c670c5f30c9650ac20d70f621cb82df9",
    "timestamp": 1776791568,
    "rand": "dH1VMcHAcH7XgXh9nlEQXIkNVohmD5iv",
    "uid": "0",
    "md5hash": "c670c5f30c9650ac20d70f621cb82df9"
}
```

---

## 调用示例

### 1. GET请求 (推荐，简单快速)

#### cURL示例
```bash
curl "https://auth-api.mxzysoa.com/generate-auth-url?filePath=/index.json"
```

#### JavaScript (Fetch API)
```javascript
async function getAuthKey() {
    const response = await fetch('https://auth-api.mxzysoa.com/generate-auth-url?filePath=/index.json');
    const authKey = await response.text();
    console.log('生成的auth_key:', authKey);
    return authKey;
}

// 使用生成的auth_key构建完整URL
async function buildAuthUrl() {
    const authKey = await getAuthKey();
    const fullUrl = `https://dl1.mxzysoa.com/index.json?auth_key=${authKey}`;
    console.log('完整鉴权URL:', fullUrl);
    return fullUrl;
}
```

#### Python (requests)
```python
import requests

response = requests.get('https://auth-api.mxzysoa.com/generate-auth-url', params={
    'filePath': '/index.json'
})
auth_key = response.text
print('生成的auth_key:', auth_key)
```

### 2. POST请求 (适合需要更多控制的场景)

#### cURL示例
```bash
curl -X POST "https://auth-api.mxzysoa.com/generate-auth-url" \
    -H "Content-Type: application/json" \
    -d '{
        "filePath": "/index.json",
        "expireTime": 3600,
        "uid": "0",
        "rawKey": true
    }'
```

#### JavaScript (Fetch API)
```javascript
async function getAuthKey() {
    const response = await fetch('https://auth-api.mxzysoa.com/generate-auth-url', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify({
            filePath: '/index.json',
            expireTime: 3600,
            uid: '0',
            rawKey: true
        })
    });
    
    const authKey = await response.text();
    console.log('生成的auth_key:', authKey);
    return authKey;
}
```

#### Python (requests)
```python
import requests

response = requests.post('https://auth-api.mxzysoa.com/generate-auth-url', json={
    'filePath': '/index.json',
    'expireTime': 3600,
    'uid': '0',
    'rawKey': true
})
auth_key = response.text
print('生成的auth_key:', auth_key)
```

---

## 响应字段说明

### auth_key结构
`{timestamp}-{rand}-{uid}-{md5hash}`

| 字段 | 类型 | 说明 |
|------|------|------|
| timestamp | integer | 十进制Unix时间戳，表示auth_key失效时间 |
| rand | string | 32位随机字符串，由大小写字母和数字组成 |
| uid | string | 用户ID，默认为0 |
| md5hash | string | 32位MD5哈希值，小写字母 |

### JSON响应字段
| 字段 | 类型 | 说明 |
|------|------|------|
| authKey | string | 完整的auth_key字符串 |
| timestamp | integer | 时间戳 |
| rand | string | 随机字符串 |
| uid | string | 用户ID |
| md5hash | string | MD5哈希值 |

---

## 错误处理

| 状态码 | 说明 | 响应 |
|--------|------|------|
| 400 | 缺少必要参数 | `请提供文件路径` (rawKey=true) 或 `{"error": "请提供文件路径"}` |
| 500 | 服务器内部错误 | 错误信息 (rawKey=true) 或 `{"error": "错误信息"}` |

---

## 使用示例

### 完整示例：生成并使用鉴权URL

```javascript
async function generateAndUseAuthUrl() {
    try {
        // 1. 调用API获取auth_key
        const response = await fetch('https://auth-api.mxzysoa.com/generate-auth-url?filePath=/video/test.mp4&expireTime=7200');
        const authKey = await response.text();
        
        console.log('生成的auth_key:', authKey);
        
        // 2. 构建完整的鉴权URL
        const authUrl = `https://dl1.mxzysoa.com/video/test.mp4?auth_key=${authKey}`;
        
        console.log('完整鉴权URL:', authUrl);
        
        // 3. 使用鉴权URL访问资源
        const resourceResponse = await fetch(authUrl);
        if (resourceResponse.ok) {
            const data = await resourceResponse.json();
            console.log('资源访问成功:', data);
        } else {
            console.error('资源访问失败:', resourceResponse.status);
        }
    } catch (error) {
        console.error('错误:', error);
    }
}

// 调用函数
generateAndUseAuthUrl();
```

---

## 技术细节

### 签名算法

```
md5hash = MD5(Path - timestamp - rand - uid - secretKey)
```

**参数**：
- `Path`：资源路径，以`/`开头
- `timestamp`：当前时间戳 + 过期时间（秒）
- `rand`：32位随机字符串
- `uid`：用户ID
- `secretKey`：预设密钥（`w8Fp2Lk9vR5mN7zT4wY6bJ1cH3gX5qP0`）

### 安全说明

1. **密钥保密**：请勿在前端代码中硬编码密钥
2. **时效性**：设置合适的expireTime，避免auth_key长期有效
3. **HTTPS**：生产环境建议使用HTTPS协议
4. **服务端验证**：API应部署在受信任的服务器上

---

## 注意事项

1. **filePath必须以/开头**：例如 `/index.json`，不要写成 `index.json`
2. **时间戳格式**：使用十进制Unix时间戳（秒）
3. **随机字符串**：不包含`-`符号
4. **uid**：根据业务需求设置，默认为0
5. **expireTime**：根据实际需求设置，默认为3600秒（1小时）

---

## 更新日志

### v1.0.0
- 初始版本
- 支持阿里云ESA A方案鉴权
- 支持GET和POST请求
