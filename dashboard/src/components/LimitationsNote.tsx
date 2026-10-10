export function LimitationsNote({ compact = false }: { compact?: boolean }) {
  return (
    <section className="card stack" aria-label="What this system does and does not prove">
      {compact ? null : <h2>Read this before relying on the register</h2>}
      <ul className="stack">
        <li><strong>The camera check is not robust liveness detection.</strong> Photos are taken with the device camera,
          but a photograph of a photograph may pass. A face comparison result is a prompt for you to look, not proof of who was there.</li>
        <li><strong>Reported location can be manipulated.</strong> The location comes from the student&apos;s phone.
          Treat an &ldquo;inside the boundary&rdquo; result as supporting evidence only.</li>
        <li><strong>Times are points of observation, not proof of continuous presence.</strong> An arrival, a checkpoint
          and a departure show where the student was reported at those moments, not in between. No hours are calculated.</li>
        <li><strong>Faculty decide.</strong> &ldquo;Recorded&rdquo; means the automated checks passed. &ldquo;Needs review&rdquo; is
          not an accusation. Only your confirm or decline, with your reason, settles a student&apos;s attendance.</li>
      </ul>
    </section>
  )
}
