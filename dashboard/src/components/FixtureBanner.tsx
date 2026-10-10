import { USE_FIXTURES } from '../api'

export function FixtureBanner() {
  if (!USE_FIXTURES) return null
  return (
    <div className="fixture-banner" role="status">
      FIXTURE MODE: sample data · changes reset on reload
    </div>
  )
}
