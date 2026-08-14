package es.jvbabi.trails.data.model

/**
 * Everything one redemption stands for: the terms, the device behind it and whose
 * device it is.
 *
 * Resolved in one read ([es.jvbabi.trails.data.ShareRepository.getSharedDevice])
 * because a client holding a capability always needs all of it, and reading the parts
 * apart could describe three different moments. [ownerUsername] is the only thing a
 * share reveals about its owner, which is why it is spelled out here instead of
 * carrying the owner's model.
 */
data class SharedDeviceModel(
    val activeShare: ActiveShareModel,
    val share: ShareModel,
    val device: DeviceModel,
    val ownerUsername: String,
)
