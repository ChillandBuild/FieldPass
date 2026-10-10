import { Link } from 'react-router-dom'
import { LimitationsNote } from '../components/LimitationsNote'

export function AboutScreen() {
  return (
    <div className="stack">
      <h1>What this system does and does not prove</h1>
      <LimitationsNote />
      <p><Link to="/activities">Back to activities</Link></p>
    </div>
  )
}
