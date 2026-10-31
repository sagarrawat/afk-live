/** NT-1 / MB-2: push alerts on this device, through the service worker at /sw.js. */
import { del, get, post } from './api';

export interface PushInfo {
  available: boolean;
  publicKey: string | null;
  devices: number;
}

export function pushSupported(): boolean {
  return typeof window !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
}

export function keyBytes(base64url: string): Uint8Array {
  const pad = '='.repeat((4 - (base64url.length % 4)) % 4);
  const raw = atob((base64url + pad).replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(raw, (c) => c.charCodeAt(0));
}

async function registration(): Promise<ServiceWorkerRegistration> {
  return (await navigator.serviceWorker.getRegistration('/')) ?? navigator.serviceWorker.register('/sw.js', { scope: '/' });
}

/** This browser's current subscription, if alerts are on here. */
export async function currentSubscription(): Promise<PushSubscription | null> {
  if (!pushSupported()) return null;
  const reg = await navigator.serviceWorker.getRegistration('/');
  return reg ? reg.pushManager.getSubscription() : null;
}

export const pushInfo = () => get<PushInfo>('/api/notifications/push');

/** Asks permission, subscribes this browser and tells the server. Returns false if the person said no. */
export async function enablePush(publicKey: string): Promise<boolean> {
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') return false;
  const reg = await registration();
  await navigator.serviceWorker.ready;
  const sub = (await reg.pushManager.getSubscription()) ?? (await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(publicKey) }));
  const j = sub.toJSON();
  await post('/api/notifications/push', { endpoint: j.endpoint, keys: { p256dh: j.keys?.p256dh, auth: j.keys?.auth } });
  return true;
}

/**
 * ON-9: this device's alerts for the "What's next" checklist. unavailable: the server has no push set up or this
 * browser can't do it; blocked: the browser said no; off; on.
 */
export function pushStateOf(
  supported: boolean,
  permission: string | undefined,
  info: Pick<PushInfo, 'available' | 'publicKey'> | null,
  subscribed: boolean,
): 'unavailable' | 'blocked' | 'off' | 'on' {
  if (!supported || !info?.available || !info.publicKey) return 'unavailable';
  if (subscribed) return 'on';
  if (permission === 'denied') return 'blocked';
  return 'off';
}

export async function disablePush(): Promise<void> {
  const sub = await currentSubscription();
  if (!sub) return;
  await del('/api/notifications/push', { endpoint: sub.endpoint });
  await sub.unsubscribe();
}
