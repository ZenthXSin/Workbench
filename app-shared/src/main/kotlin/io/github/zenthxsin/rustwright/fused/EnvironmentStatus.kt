package io.github.zenthxsin.rustwright.fused

import io.github.zenthxsin.rustwright.core.shell.LinuxEnvironment
import io.github.zenthxsin.rustwright.linux.impl.LinuxProvisioner
import io.github.zenthxsin.rustwright.linux.impl.LinuxStatus

internal fun environmentStatus(linux: LinuxEnvironment?, provisioner: LinuxProvisioner?): EnvStatus =
    LinuxStatus.of(linux, provisioner).let { EnvStatus(it.ready, it.progress, it.detail) }
