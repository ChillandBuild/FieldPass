// Fixed timezone so local-time conversion tests are deterministic (IST = UTC+5:30, no DST).
export default function setup(): void {
  process.env.TZ = 'Asia/Kolkata'
}
