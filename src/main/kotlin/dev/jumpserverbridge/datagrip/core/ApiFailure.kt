package dev.jumpserverbridge.datagrip.core

enum class FailureKind(val description: String) {
    INVALID_CONFIGURATION("请检查 JumpServer 设置和数据源中的资产、授权账号。"),
    INVALID_RESPONSE("JumpServer 返回的数据格式不符合预期。"),
    UNAUTHENTICATED("JumpServer API 密钥认证失败。"),
    FORBIDDEN("JumpServer 拒绝访问，请检查授权及 IP 限制。"),
    APPROVAL_REQUIRED("此连接需要先在 JumpServer 中完成审批。"),
    TIMEOUT("JumpServer API 请求超时。"),
    NETWORK("JumpServer API 请求失败，请检查网络和 TLS 配置。")
}

class ApiFailure(val kind: FailureKind, val status: Int? = null) :
    RuntimeException(kind.description + (status?.let { " (HTTP $it)" } ?: ""))
