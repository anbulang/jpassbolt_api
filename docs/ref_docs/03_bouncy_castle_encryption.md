# Bouncy Castle 加密实现详解

本文档详细说明 JPassbolt 中使用 Bouncy Castle 库实现 OpenPGP 加密/解密的原理和实现细节。

## 概述

Passbolt 使用 OpenPGP (Pretty Good Privacy) 标准进行端到端加密。在 PHP 参考实现中，使用 `gnupg` PHP 扩展调用系统的 GPG 命令。在 Java 实现中，我们使用 **Bouncy Castle** 库进行纯 Java 的 PGP 操作。

> [!IMPORTANT]
> **设计原则**: 绝对不要使用 `Runtime.exec("gpg ...")` 调用外部命令。必须始终使用 Bouncy Castle 库，确保跨平台可移植性。

## 依赖配置

**pom.xml 依赖**:
```xml
<!-- Bouncy Castle Provider -->
<dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcprov-jdk15on</artifactId>
    <version>1.70</version>
</dependency>

<!-- Bouncy Castle PKIX -->
<dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcpkix-jdk15on</artifactId>
    <version>1.70</version>
</dependency>

<!-- Bouncy Castle OpenPGP -->
<dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcpg-jdk15on</artifactId>
    <version>1.70</version>
</dependency>
```

---

## Java 实现

### GpgServiceImpl.java

**文件**: [GpgServiceImpl.java](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/src/main/java/com/jpassbolt/api/service/GpgServiceImpl.java)

#### 类结构

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class GpgServiceImpl implements GpgService {
    private final GpgProperties gpgProperties;      // 配置属性
    private final ResourceLoader resourceLoader;    // 资源加载器
    
    private PGPSecretKeyRing serverSecretKeyRing;  // 服务器私钥环
    private PGPPublicKeyRing serverPublicKeyRing;  // 服务器公钥环
    private String serverPublicKeyArmored;          // ASCII Armor 格式公钥
}
```

#### 1. 初始化 (Provider 注册)

```java
@PostConstruct
public void init() {
    // 注册 Bouncy Castle Provider
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
        Security.addProvider(new BouncyCastleProvider());
    }
    
    loadServerKeys();
    log.info("GPG keys loaded. Fingerprint: {}", getServerKeyFingerprint());
}
```

#### 2. 密钥加载

```java
private void loadServerKeys() throws IOException, PGPException {
    // 加载私钥 (从 classpath:gpg/server_private.asc)
    try (InputStream privateKeyStream = resourceLoader.getResource(
            gpgProperties.getServerKey().getPrivateLocation()).getInputStream()) {
        PGPSecretKeyRingCollection secretKeyRings = new PGPSecretKeyRingCollection(
                PGPUtil.getDecoderStream(privateKeyStream),
                new JcaKeyFingerprintCalculator());
        serverSecretKeyRing = secretKeyRings.iterator().next();
    }

    // 加载公钥 (从 classpath:gpg/server_public.asc)
    try (InputStream publicKeyStream = resourceLoader.getResource(
            gpgProperties.getServerKey().getPublicLocation()).getInputStream()) {
        PGPPublicKeyRingCollection publicKeyRings = new PGPPublicKeyRingCollection(
                PGPUtil.getDecoderStream(publicKeyStream),
                new JcaKeyFingerprintCalculator());
        serverPublicKeyRing = publicKeyRings.iterator().next();
    }
}
```

#### 3. 加密操作

```java
@Override
public String encrypt(String data, String userPublicKey) {
    // 1. 解析用户公钥
    PGPPublicKey encryptionKey = getEncryptionKey(userPublicKey);

    // 2. 创建输出流 (ASCII Armor 格式)
    ByteArrayOutputStream encryptedOut = new ByteArrayOutputStream();
    ArmoredOutputStream armoredOut = new ArmoredOutputStream(encryptedOut);

    // 3. 创建加密数据生成器 (AES-256)
    PGPEncryptedDataGenerator encryptedDataGenerator = new PGPEncryptedDataGenerator(
            new JcePGPDataEncryptorBuilder(PGPEncryptedData.AES_256)
                    .setWithIntegrityPacket(true)       // 启用完整性检查
                    .setSecureRandom(new SecureRandom())
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME));

    // 4. 添加公钥加密方法
    encryptedDataGenerator.addMethod(
            new JcePublicKeyKeyEncryptionMethodGenerator(encryptionKey)
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME));

    // 5. 写入加密数据
    byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
    try (OutputStream encryptedStream = encryptedDataGenerator.open(armoredOut, new byte[4096])) {
        PGPLiteralDataGenerator literalDataGenerator = new PGPLiteralDataGenerator();
        try (OutputStream literalOut = literalDataGenerator.open(
                encryptedStream,
                PGPLiteralData.UTF8,
                PGPLiteralData.CONSOLE,
                dataBytes.length,
                new Date())) {
            literalOut.write(dataBytes);
        }
    }

    armoredOut.close();
    return encryptedOut.toString(StandardCharsets.UTF_8);
}
```

**加密流程图**:

```
┌────────────────────────────────────────────────────────────────────────────┐
│                           PGP 加密流程                                      │
├────────────────────────────────────────────────────────────────────────────┤
│                                                                            │
│  明文数据                                                                   │
│     │                                                                      │
│     ▼                                                                      │
│  ┌─────────────────┐                                                       │
│  │ LiteralData     │  ← 封装原始数据 (UTF-8, 文件名, 日期)                  │
│  └────────┬────────┘                                                       │
│           │                                                                │
│           ▼                                                                │
│  ┌─────────────────┐      ┌──────────────┐                                │
│  │ 随机会话密钥     │─────►│ AES-256 加密 │                                 │
│  │ (Session Key)   │      └──────────────┘                                │
│  └────────┬────────┘              │                                        │
│           │                       ▼                                        │
│           ▼              ┌─────────────────┐                               │
│  ┌─────────────────┐     │ 加密后的数据体   │                               │
│  │ 用户公钥加密     │     └────────┬────────┘                               │
│  │ 会话密钥        │              │                                        │
│  └────────┬────────┘              │                                        │
│           │                       │                                        │
│           ▼                       ▼                                        │
│  ┌─────────────────────────────────────────┐                               │
│  │         PGP 加密消息包 (组合)            │                               │
│  └────────────────────┬────────────────────┘                               │
│                       │                                                    │
│                       ▼                                                    │
│  ┌─────────────────────────────────────────┐                               │
│  │       ASCII Armor 编码输出               │                               │
│  │       -----BEGIN PGP MESSAGE-----       │                               │
│  └─────────────────────────────────────────┘                               │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

#### 4. 解密操作

```java
@Override
public String decrypt(String encryptedData) {
    // 1. 解码 ASCII Armor 数据
    InputStream inputStream = PGPUtil.getDecoderStream(
            new ByteArrayInputStream(encryptedData.getBytes(StandardCharsets.UTF_8)));
    
    // 2. 解析 PGP 对象
    JcaPGPObjectFactory pgpObjectFactory = new JcaPGPObjectFactory(inputStream);
    Object object = pgpObjectFactory.nextObject();
    
    // 3. 获取加密数据列表
    PGPEncryptedDataList encryptedDataList;
    if (object instanceof PGPEncryptedDataList) {
        encryptedDataList = (PGPEncryptedDataList) object;
    } else {
        encryptedDataList = (PGPEncryptedDataList) pgpObjectFactory.nextObject();
    }
    
    // 4. 查找匹配的私钥
    PGPPrivateKey privateKey = null;
    PGPPublicKeyEncryptedData encryptedDataPacket = null;
    
    Iterator<PGPEncryptedData> iterator = encryptedDataList.getEncryptedDataObjects();
    while (privateKey == null && iterator.hasNext()) {
        encryptedDataPacket = (PGPPublicKeyEncryptedData) iterator.next();
        privateKey = findPrivateKey(encryptedDataPacket.getKeyID());
    }
    
    // 5. 解密数据
    InputStream decryptedStream = encryptedDataPacket.getDataStream(
            new JcePublicKeyDataDecryptorFactoryBuilder()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(privateKey));
    
    // 6. 解析 LiteralData 获取明文
    JcaPGPObjectFactory decryptedFactory = new JcaPGPObjectFactory(decryptedStream);
    Object decryptedObject = decryptedFactory.nextObject();
    
    // 处理压缩数据
    if (decryptedObject instanceof PGPCompressedData) {
        PGPCompressedData compressedData = (PGPCompressedData) decryptedObject;
        decryptedFactory = new JcaPGPObjectFactory(compressedData.getDataStream());
        decryptedObject = decryptedFactory.nextObject();
    }
    
    // 提取明文
    if (decryptedObject instanceof PGPLiteralData) {
        PGPLiteralData literalData = (PGPLiteralData) decryptedObject;
        return new String(literalData.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
    
    throw new RuntimeException("Unexpected PGP object type");
}
```

#### 5. 私钥提取

```java
private PGPPrivateKey findPrivateKey(long keyId) throws PGPException {
    PGPSecretKey secretKey = serverSecretKeyRing.getSecretKey(keyId);
    if (secretKey == null) {
        return null;
    }
    
    // 使用密码解锁私钥
    char[] passphrase = gpgProperties.getServerKey().getPassphrase().toCharArray();
    return secretKey.extractPrivateKey(
            new JcePBESecretKeyDecryptorBuilder()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(passphrase));
}
```

---

## PHP 参考实现

### OpenPGPBackendInterface.php

**文件**: [OpenPGPBackendInterface.php](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/passbolt_api_ref/src/Utility/OpenPGP/OpenPGPBackendInterface.php)

PHP 定义的加密接口：

```php
interface OpenPGPBackendInterface {
    // 消息标记常量
    public const MESSAGE_MARKER = 'PGP MESSAGE';
    public const PUBLIC_KEY_MARKER = 'PGP PUBLIC KEY BLOCK';
    public const PRIVATE_KEY_MARKER = 'PGP PRIVATE KEY BLOCK';
    
    // 加密相关
    public function setEncryptKey(string $armoredKey): bool;
    public function setEncryptKeyFromFingerprint(string $fingerprint): bool;
    public function encrypt(string $text, bool $sign = false): string;
    public function encryptSign(string $text): string;
    
    // 解密相关
    public function setDecryptKey(string $armoredKey, string $passphrase): bool;
    public function setDecryptKeyFromFingerprint(string $fingerprint, string $passphrase): bool;
    public function decrypt(string $text, bool $verifySignature = false): string;
    
    // 签名/验证
    public function setSignKey(string $armoredKey, string $passphrase): bool;
    public function setSignKeyFromFingerprint(string $fingerprint, string $passphrase): bool;
    public function sign(string $text): string;
    public function verify(string $signedText, ?string &$plainText = null): array;
    
    // 密钥管理
    public function importKeyIntoKeyring(string $armoredKey): string;
    public function isKeyInKeyring(string $fingerprint): bool;
    public function getKeyInfo(string $armoredKey): array;
}
```

### OpenPGPBackend.php

**文件**: [OpenPGPBackend.php](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/passbolt_api_ref/src/Utility/OpenPGP/OpenPGPBackend.php)

```php
abstract class OpenPGPBackend implements OpenPGPBackendInterface {
    protected ?string $_decryptKeyFingerprint = null;
    protected ?string $_encryptKeyFingerprint = null;
    protected ?string $_signKeyFingerprint = null;
    protected ?string $_verifyKeyFingerprint = null;
    
    // 验证消息格式
    public function isValidMessage(string $armored): bool {
        try {
            $this->assertGpgMarker($armored, self::MESSAGE_MARKER);
        } catch (CakeException $e) {
            return false;
        }
        return $this->unarmor($armored, self::MESSAGE_MARKER) !== false;
    }
    
    // 验证指纹格式 (40 字符十六进制)
    public static function isValidFingerprint(mixed $fingerprint = null): bool {
        if (!isset($fingerprint) || !is_string($fingerprint)) {
            return false;
        }
        return preg_match('/^[A-F0-9]{40}$/', $fingerprint) === 1;
    }
    
    // 指纹转 Key ID (取后 16 位)
    public static function fingerprintToKeyId(string $fingerprint): string {
        if (strlen($fingerprint) !== 40) {
            throw new Exception('Invalid fingerprint.');
        }
        return substr($fingerprint, -16);
    }
}
```

---

## Java vs PHP 对照表

| 功能 | Java (Bouncy Castle) | PHP (GnuPG) |
|------|---------------------|-------------|
| 加密 | `PGPEncryptedDataGenerator` | `gnupg_encrypt()` |
| 解密 | `JcePublicKeyDataDecryptorFactoryBuilder` | `gnupg_decrypt()` |
| 签名 | `PGPSignatureGenerator` | `gnupg_sign()` |
| 验证 | `PGPSignature.verify()` | `gnupg_verify()` |
| 导入密钥 | `PGPPublicKeyRingCollection` | `gnupg_import()` |
| 密钥信息 | `PGPPublicKey.getFingerprint()` | `gnupg_keyinfo()` |

---

## 配置文件

### application.yml

```yaml
jpassbolt:
  gpg:
    server-key:
      private-location: classpath:gpg/server_private.asc
      public-location: classpath:gpg/server_public.asc
      passphrase: password
```

### GpgProperties.java

**文件**: [GpgProperties.java](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/src/main/java/com/jpassbolt/api/config/GpgProperties.java)

```java
@Data
@Configuration
@ConfigurationProperties(prefix = "jpassbolt.gpg")
public class GpgProperties {
    private ServerKey serverKey = new ServerKey();
    
    @Data
    public static class ServerKey {
        private String privateLocation;   // 私钥路径
        private String publicLocation;    // 公钥路径
        private String passphrase;        // 私钥密码
    }
}
```

---

## 密钥文件格式

### ASCII Armor 公钥示例

```
-----BEGIN PGP PUBLIC KEY BLOCK-----

xsDNBGXR0mMBDACkuPuDcO1xHOvndM6yvfBXQN0gW8btdw97uipRqplxQgGj
... (Base64 编码的密钥数据)
=XXXX
-----END PGP PUBLIC KEY BLOCK-----
```

### 密钥结构

```
PGP 密钥环 (KeyRing)
├── 主密钥 (Master Key)
│   ├── 指纹 (Fingerprint): 40 字符十六进制
│   ├── Key ID: 16 字符 (指纹后 16 位)
│   ├── 创建时间
│   └── 用户 ID (UID): "Name <email>"
│
└── 子密钥 (Subkeys)
    ├── 加密子密钥 (Encryption)
    └── 签名子密钥 (Signing)
```

---

## 相关文件

| 类型 | 文件 |
|------|------|
| Java 实现 | [GpgServiceImpl.java](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/src/main/java/com/jpassbolt/api/service/GpgServiceImpl.java) |
| Java 接口 | [GpgService.java](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/src/main/java/com/jpassbolt/api/service/GpgService.java) |
| Java 配置 | [GpgProperties.java](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/src/main/java/com/jpassbolt/api/config/GpgProperties.java) |
| PHP 接口 | [OpenPGPBackendInterface.php](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/passbolt_api_ref/src/Utility/OpenPGP/OpenPGPBackendInterface.php) |
| PHP 抽象类 | [OpenPGPBackend.php](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/passbolt_api_ref/src/Utility/OpenPGP/OpenPGPBackend.php) |
| PHP GnuPG 实现 | [Gnupg.php](file:///Users/chaucer/Code/gitlab/jpassbolt/jpassbolt_api/passbolt_api_ref/src/Utility/OpenPGP/Backends/Gnupg.php) |
