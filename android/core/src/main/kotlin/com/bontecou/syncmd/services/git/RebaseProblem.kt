package com.bontecou.syncmd.services.git

/** Stable recovery identity; platform UIs localize this without parsing exception messages. */
enum class RebaseProblem { ACTIVE_OPERATION, LOCAL_BRANCH_REQUIRED, LOCAL_CHANGES, UPSTREAM_REQUIRED }

class RebaseFailure(val problem: RebaseProblem, message: String) : IllegalStateException(message)
