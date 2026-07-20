package ba.sake.basamake.core

import ba.sake.tupson.{given, *}

final case class BasamakeStatus(
    bspConnections: List[BspConnectionStatus]
) derives JsonRW

final case class BspConnectionStatus(
    configPath: String,
    state: String,
    targets: List[BspTargetStatus]
) derives JsonRW

final case class BspTargetStatus(
    id: String,
    semanticdbEnabled: Option[Boolean],
    bestEffortEnabled: Option[Boolean]
) derives JsonRW
