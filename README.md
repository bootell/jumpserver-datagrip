# JumpServer Database Auth for DataGrip

在 DataGrip 中使用个人 API Key 连接 JumpServer 授权的 MySQL 数据库。每次建立连接时自动获取临时凭据，无需打开网页复制账号密码。

## 环境要求

- DataGrip 2026.2.6 及以上，JumpServer 3.10 系列。
- 已申请个人 API Access Key，并获得对应数据库和账号的访问权限。
- 使用 DataGrip 官方 MySQL 驱动，支持 Aurora MySQL 识别；暂不支持 AWS Wrapper 驱动。

插件使用 JetBrains Experimental 认证接口，不限制 IDE 版本上限，后续版本兼容性由自动检查持续验证。

## 安装

1. 在 GitHub **Releases** 页面下载插件 ZIP，或自行构建。
2. 打开 `设置 → 插件 → 齿轮菜单 → 从磁盘安装插件`，选择 ZIP。
3. 重启 DataGrip。

## 使用

1. 打开 `设置 → 工具 → JumpServer`，填写服务器 HTTPS 地址、Access Key ID 和 Secret，验证连接后保存。
2. 在 `帮助 → 查找操作…` 中搜索 **从 JumpServer 添加连接**。
3. 搜索数据库资产，选择授权账号，点击 **创建连接**。
4. 在 DataGrip 数据源窗口中点击 **测试连接**，确认后保存。

后续直接点击该数据源即可连接，插件会自动申请新的临时凭据。API Secret 保存在 IDE 密码库中；同一服务器和 Key ID 下，Secret 留空可保留原值。

## 连接设置

- 默认名称为 `资产名@数据库用户名`，数据库来自 JumpServer 资产配置。
- 在原生 **数据库** 字段修改目标库，在 **架构** 页选择需要显示的库。
- 新建连接默认不勾选只读，启用 30 秒保持连接查询；可在 **选项** 页自行调整。
- 已有连接的手动配置不会被默认值覆盖。

## 本地构建

需要 Java 25，Gradle 会自动下载编译所需的 DataGrip SDK。

```sh
./gradlew test buildPlugin
```

安装包位于 `build/distributions/`。使用本地 SDK 时可添加 `-PlocalIdePath=/path/to/DataGrip.app`。

GitHub Actions 会在提交、PR、手动触发及每天定时执行测试和官方 Plugin Verifier，检查最低支持版本、2026.2.6.1 与最新正式版 DataGrip。

## 自动构建与发布

每次推送代码和提交 PR 都会自动编译、测试并检查 IDE 兼容性，安装包可在 Actions 运行页面下载。只有推送版本标签才会自动创建正式 Release，并上传可直接安装的插件 ZIP；标签必须与 `build.gradle.kts` 中的版本一致。

例如当前版本 `0.0.1`，先提交代码，再执行：

```sh
git tag v0.0.1
git push origin v0.0.1
```

后续发布先修改项目版本号并提交，再推送对应的 `v版本号` 标签。发布过程使用 GitHub 自带令牌，无需配置个人 Token。

## 许可证

[MIT License](LICENSE)。第三方依赖 Gson、Error Prone（Google）和 Gradle Wrapper（Gradle）使用 [Apache-2.0](src/main/resources/META-INF/licenses/Apache-2.0.txt) 许可。
