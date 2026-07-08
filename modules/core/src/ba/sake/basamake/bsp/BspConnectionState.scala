package ba.sake.basamake.bsp

enum BspConnectionState:
  case Idle
  case Spawning
  case Handshaking
  case Connected
  case BackoffWait
  case Reloading
  case Failed
  case Detached
