package ba.sake.basamake.core

enum ConnectionState:
  case Idle
  case Spawning
  case Handshaking
  case Connected
  case BackoffWait
  case Reloading
  case Failed
  case Detached
