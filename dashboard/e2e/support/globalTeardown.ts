import { stopBackend } from './backend'

export default function globalTeardown(): void {
  stopBackend()
}
