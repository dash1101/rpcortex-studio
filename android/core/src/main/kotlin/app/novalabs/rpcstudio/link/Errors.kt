package app.novalabs.rpcstudio.link

/**
 * Every error the link client raises is an [RpcError], so a caller can catch that one type and show
 * the message: each is written to be read by a person. The same family as the desktop tool's.
 */
open class RpcError(message: String) : Exception(message)

/** The port went away: the board rebooted, was unplugged, or reset. */
class DeviceGone(message: String = "the device has gone") : RpcError(message)

/** The device stopped answering within the time allowed. */
class DeviceTimeout(message: String) : RpcError(message)

/** The device is at a login prompt and no account was given. */
class LoginRequired(message: String) : RpcError(message)

/** The device refused the account or the password. */
class LoginFailed(message: String) : RpcError(message)

/** The firmware on the device cannot do this (it predates link mode). */
class NotSupported(message: String) : RpcError(message)

/** The device answered something the protocol does not allow. */
class ProtocolError(message: String) : RpcError(message)

/** What arrived is not what was sent. */
class DigestMismatch(message: String) : RpcError(message)

/** The device answered a request with `ERR <code> <message>`. */
class LinkError(val code: String, val detail: String) :
    RpcError(if (detail.isNotEmpty()) "$detail ($code)" else code)
