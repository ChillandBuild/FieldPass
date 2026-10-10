import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { VenuePicker } from '../src/components/VenuePicker'
import { searchVenues, type VenuePoint } from '../src/lib/venueSearch'

vi.mock('../src/lib/venueSearch', () => ({ searchVenues: vi.fn() }))
vi.mock('../src/components/VenueMap', () => ({ VenueMap: ({ onMove }: { onMove: (point: VenuePoint) => void }) =>
  <button type="button" onClick={() => onMove({ lat: 12.972, lng: 77.595 })}>Move circle</button> }))
afterEach(() => { cleanup(); vi.resetAllMocks() })

describe('venue selection', () => {
  it('keeps separate radii for added locations and requires each to be placed', () => {
    const onRegionsChange = vi.fn()
    render(<VenuePicker radius={25} onChange={vi.fn()} onRegionsChange={onRegionsChange} onName={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Move circle' }))
    fireEvent.change(screen.getByRole('slider'), { target: { value: '30' } })
    fireEvent.click(screen.getByRole('button', { name: '+ Add location' }))
    expect(screen.getByRole('button', { name: 'Confirm venue location' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Move circle' }))
    fireEvent.change(screen.getByRole('slider'), { target: { value: '80' } })
    fireEvent.click(screen.getByRole('button', { name: /^Location 1$/ }))
    expect(screen.getByRole('slider')).toHaveValue('30')
    fireEvent.click(screen.getByRole('button', { name: 'Confirm venue location' }))
    expect(onRegionsChange).toHaveBeenLastCalledWith([
      { venue_lat: 12.972, venue_lng: 77.595, radius_m: 30 },
      { venue_lat: 12.972, venue_lng: 77.595, radius_m: 80 },
    ])
    fireEvent.click(screen.getByRole('button', { name: 'Remove selected location' }))
    expect(onRegionsChange).toHaveBeenLastCalledWith(null)
  })
  it('requires confirmation and clears it when the circle moves', async () => {
    vi.mocked(searchVenues).mockResolvedValue([{ name: 'Lalbagh', address: 'Lalbagh, Bengaluru', lat: 12.97, lng: 77.59 }])
    const onChange = vi.fn()
    const onName = vi.fn()
    render(<VenuePicker radius={100} onChange={onChange} onName={onName} />)
    expect(screen.getByRole('button', { name: 'Confirm venue location' })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Search venue or address'), { target: { value: 'Lalbagh Bengaluru' } })
    expect(searchVenues).not.toHaveBeenCalled()
    fireEvent.keyDown(screen.getByLabelText('Search venue or address'), { key: 'Enter' })
    fireEvent.click(await screen.findByRole('button', { name: /Lalbagh.*Bengaluru/ }))
    expect(onName).toHaveBeenCalledWith('Lalbagh')
    expect(onChange).toHaveBeenLastCalledWith(null)
    fireEvent.click(screen.getByRole('button', { name: 'Confirm venue location' }))
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ lat: 12.97, lng: 77.59 }))
    fireEvent.click(screen.getByRole('button', { name: 'Move circle' }))
    expect(onChange).toHaveBeenLastCalledWith(null)
    expect(screen.getByRole('button', { name: 'Confirm venue location' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Confirm venue location' }))
    expect(onChange).toHaveBeenLastCalledWith({ lat: 12.972, lng: 77.595 })
  })

  it('handles no results and search outages while allowing map selection', async () => {
    vi.mocked(searchVenues).mockResolvedValueOnce([{ name: 'Campus', address: 'Campus, city', lat: 12.97, lng: 77.59 }])
      .mockResolvedValueOnce([]).mockRejectedValueOnce(new Error('offline'))
    render(<VenuePicker radius={100} onChange={vi.fn()} onName={vi.fn()} />)
    fireEvent.change(screen.getByLabelText('Search venue or address'), { target: { value: 'Unknown venue' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search venue' }))
    fireEvent.click(await screen.findByRole('button', { name: /Campus.*city/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Search venue' }))
    expect(await screen.findByText(/No venues found/)).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Search venue' }))
    expect(await screen.findByText(/Could not search for venues/)).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Move circle' }))
    expect(screen.getByRole('button', { name: 'Confirm venue location' })).toBeEnabled()
  })

  it('prevents empty searches and duplicate requests while searching', async () => {
    let resolve!: (value: []) => void
    vi.mocked(searchVenues).mockReturnValue(new Promise((done) => { resolve = done }))
    render(<VenuePicker radius={100} onChange={vi.fn()} onName={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Search venue' }))
    expect(searchVenues).not.toHaveBeenCalled()
    fireEvent.change(screen.getByLabelText('Search venue or address'), { target: { value: 'Bengaluru' } })
    fireEvent.click(screen.getByRole('button', { name: 'Search venue' }))
    expect(screen.getByRole('button', { name: 'Searching…' })).toBeDisabled()
    resolve([])
    await waitFor(() => expect(screen.getByRole('button', { name: 'Search venue' })).toBeEnabled())
    expect(searchVenues).toHaveBeenCalledTimes(1)
  })
})
