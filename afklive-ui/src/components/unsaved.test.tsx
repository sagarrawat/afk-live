import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { createMemoryRouter, Link, RouterProvider } from 'react-router-dom';
import { LeaveGuard, listText, SaveBar, UnsavedChangesProvider, useUnsavedChanges } from './unsaved';

beforeAll(() => {
  HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
    this.open = true;
  };
  HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) {
    this.open = false;
  };
});

function Editor() {
  const [dirty, setDirty] = useState(false);
  useUnsavedChanges(dirty, 'the playlist order');
  return (
    <>
      <button onClick={() => setDirty(true)}>Move a video</button>
      <button onClick={() => setDirty(false)}>Save</button>
      <Link to="/studio/streams/7/overlays">Overlays</Link>
    </>
  );
}

const show = () => {
  const router = createMemoryRouter(
    [
      {
        path: '*',
        element: (
          <UnsavedChangesProvider>
            <LeaveGuard />
            <Editor />
          </UnsavedChangesProvider>
        ),
      },
    ],
    { initialEntries: ['/studio/streams/7/playlist'] },
  );
  render(<RouterProvider router={router} />);
  return router;
};

describe('unsaved changes are never lost without a word (UX audit #9)', () => {
  it('lets you leave freely when nothing is unsaved', async () => {
    const router = show();
    await userEvent.click(screen.getByRole('link', { name: 'Overlays' }));
    expect(router.state.location.pathname).toBe('/studio/streams/7/overlays');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('asks before another tab replaces unsaved changes, and keeps them on "Keep editing"', async () => {
    const router = show();
    await userEvent.click(screen.getByRole('button', { name: 'Move a video' }));
    await userEvent.click(screen.getByRole('link', { name: 'Overlays' }));
    expect(screen.getByRole('dialog', { name: 'Leave without saving?' })).toHaveTextContent('You have unsaved changes to the playlist order.');
    await userEvent.click(screen.getByRole('button', { name: 'Keep editing' }));
    expect(router.state.location.pathname).toBe('/studio/streams/7/playlist');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('leaves on "Leave without saving"', async () => {
    const router = show();
    await userEvent.click(screen.getByRole('button', { name: 'Move a video' }));
    await userEvent.click(screen.getByRole('link', { name: 'Overlays' }));
    await userEvent.click(screen.getByRole('button', { name: 'Leave without saving' }));
    expect(router.state.location.pathname).toBe('/studio/streams/7/overlays');
  });

  it('asks the browser before closing or reloading the page', async () => {
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Move a video' }));
    const e = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(e);
    expect(e.defaultPrevented).toBe(true);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    const after = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(after);
    expect(after.defaultPrevented).toBe(false);
  });

  it('names everything unsaved in plain words', () => {
    expect(listText(['overlays'])).toBe('overlays');
    expect(listText(['overlays', 'scenes', 'overlays'])).toBe('overlays and scenes');
    expect(listText(['a', 'b', 'c'])).toBe('a, b and c');
  });

  it('shows Save and Undo only while something is unsaved', async () => {
    const saved = vi.fn();
    const { rerender } = render(<SaveBar dirty={false} what="overlays" onSave={saved} onUndo={() => {}} />);
    expect(screen.queryByRole('region', { name: 'Unsaved changes' })).not.toBeInTheDocument();
    rerender(<SaveBar dirty what="overlays" onSave={saved} onUndo={() => {}} />);
    expect(screen.getByRole('region', { name: 'Unsaved changes' })).toHaveTextContent('Unsaved changes to overlays');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(saved).toHaveBeenCalled();
  });
});
