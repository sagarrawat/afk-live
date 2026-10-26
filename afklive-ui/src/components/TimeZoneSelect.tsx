import { useMemo, useState } from 'react';
import { Input, Select } from './ui';

/** Every IANA zone the browser knows; empty where the browser can't list them. */
export function allTimeZones(): string[] {
  try {
    return (Intl as unknown as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.('timeZone') ?? [];
  } catch {
    return [];
  }
}

/** Words people search for that aren't in the zone's name. */
const ALSO: Record<string, string> = {
  'Asia/Kolkata': 'india ist calcutta mumbai delhi bengaluru bangalore chennai hyderabad',
  'Asia/Dubai': 'uae gst',
  'Asia/Karachi': 'pakistan',
  'Asia/Dhaka': 'bangladesh',
  'Asia/Kathmandu': 'nepal',
  'Asia/Colombo': 'sri lanka',
  'Europe/London': 'uk britain england gmt bst',
  'America/New_York': 'us eastern et',
  'America/Chicago': 'us central ct',
  'America/Denver': 'us mountain mt',
  'America/Los_Angeles': 'us pacific pt california',
  'Asia/Singapore': 'sgt',
  'Australia/Sydney': 'aest',
  UTC: 'gmt utc universal',
};

/** "Asia/Kolkata" → "Asia / Kolkata (GMT+5:30)". */
export function zoneLabel(zone: string, at = new Date()): string {
  let offset = '';
  try {
    offset = new Intl.DateTimeFormat('en-US', { timeZone: zone, timeZoneName: 'shortOffset' }).formatToParts(at).find((p) => p.type === 'timeZoneName')?.value ?? '';
  } catch {
    offset = '';
  }
  const name = zone.replace(/_/g, ' ').replace(/\//g, ' / ');
  return offset ? `${name} (${offset})` : name;
}

/** Zones matching every word of {@code query} (in the name, a known alias, or the offset). */
export function searchZones(zones: string[], query: string): string[] {
  const words = query.toLowerCase().trim().split(/\s+/).filter(Boolean);
  if (!words.length) return zones;
  return zones.filter((z) => {
    const hay = `${z.replace(/_/g, ' ').toLowerCase()} ${ALSO[z] ?? ''}`;
    return words.every((w) => hay.includes(w));
  });
}

/**
 * UX audit #21: a time zone from the browser's list, with a search box (no typing "Asia/Kolkata" by
 * hand). Searching picks the first match; the list then narrows to what matches. Falls back to a text
 * field where the browser can't list zones.
 */
export function TimeZoneSelect({ id, value, onChange }: { id: string; value: string; onChange: (zone: string) => void }) {
  const zones = useMemo(allTimeZones, []);
  const [query, setQuery] = useState('');
  const shown = useMemo(() => searchZones(zones, query), [zones, query]);
  const labels = useMemo(() => new Map(zones.map((z) => [z, zoneLabel(z)])), [zones]);
  if (zones.length === 0) {
    return <Input id={id} value={value} placeholder="Asia/Kolkata" onChange={(e) => onChange(e.target.value)} />;
  }
  const options = value && !shown.includes(value) ? [value, ...shown] : shown;
  return (
    <div className="space-y-2">
      <Input
        type="search"
        aria-label="Search time zones"
        placeholder="Search a city or country"
        value={query}
        onChange={(e) => {
          const q = e.target.value;
          setQuery(q);
          const matches = searchZones(zones, q);
          if (q.trim() && matches.length > 0 && !matches.includes(value)) onChange(matches[0]);
        }}
      />
      <Select id={id} value={value} onChange={(e) => onChange(e.target.value)}>
        {options.map((z) => (
          <option key={z} value={z}>
            {labels.get(z) ?? zoneLabel(z)}
          </option>
        ))}
      </Select>
      {query.trim() && shown.length === 0 && <p className="text-xs text-muted">No time zone matches &ldquo;{query.trim()}&rdquo;. Try a nearby big city.</p>}
    </div>
  );
}
