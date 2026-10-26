import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { allTimeZones, searchZones, TimeZoneSelect, zoneLabel } from './TimeZoneSelect';

function Picker({ start = 'Asia/Kolkata' }: { start?: string }) {
  const [tz, setTz] = useState(start);
  return (
    <>
      <label htmlFor="tz">Time zone</label>
      <TimeZoneSelect id="tz" value={tz} onChange={setTz} />
      <output>{tz}</output>
    </>
  );
}

describe('time zone picker (UX audit #21)', () => {
  it("lists the browser's zones instead of a free-text field", () => {
    expect(allTimeZones()).toContain('Asia/Kolkata');
    render(<Picker />);
    const select = screen.getByRole('combobox', { name: 'Time zone' });
    expect(select).toHaveValue('Asia/Kolkata');
    expect(screen.getByRole('option', { name: /Asia \/ Kolkata \(GMT\+5:30\)/ })).toBeInTheDocument();
  });

  it('finds a zone by city or country and picks the first match', async () => {
    render(<Picker />);
    await userEvent.type(screen.getByRole('searchbox', { name: 'Search time zones' }), 'new york');
    expect(screen.getByRole('combobox', { name: 'Time zone' })).toHaveValue('America/New_York');
    expect(screen.getByText('America/New_York', { selector: 'output' })).toBeInTheDocument();
  });

  it('says so when nothing matches, and keeps the current zone', async () => {
    render(<Picker />);
    await userEvent.type(screen.getByRole('searchbox', { name: 'Search time zones' }), 'zzzz');
    expect(screen.getByText(/No time zone matches/)).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Time zone' })).toHaveValue('Asia/Kolkata');
  });

  it('knows the names people search for', () => {
    const zones = ['Asia/Kolkata', 'Europe/London', 'America/New_York'];
    expect(searchZones(zones, 'india')).toEqual(['Asia/Kolkata']);
    expect(searchZones(zones, 'mumbai')).toEqual(['Asia/Kolkata']);
    expect(searchZones(zones, 'UK')).toEqual(['Europe/London']);
    expect(searchZones(zones, '')).toEqual(zones);
    expect(zoneLabel('UTC')).toMatch(/^UTC/);
  });
});
