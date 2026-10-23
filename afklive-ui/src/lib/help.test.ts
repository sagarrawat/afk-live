import { HELP, helpForProblem } from './help';

describe('linking problems to help articles (MK-4)', () => {
  it('sends key problems to where to find the YouTube key', () => {
    expect(helpForProblem('The platform refused the stream key. Copy the key again from the platform and save it.', 'YOUTUBE')).toBe(HELP.streamKey);
  });

  it('sends other YouTube problems to the "live here but not on YouTube" checklist', () => {
    expect(helpForProblem("Couldn't reach the server. Check the server address.", 'YOUTUBE')).toBe(HELP.notOnYouTube);
  });

  it('stays quiet for our own hiccups, empty messages and other platforms', () => {
    expect(helpForProblem("The test couldn't run on our side. Please try again.", 'YOUTUBE')).toBeNull();
    expect(helpForProblem('The test is taking too long. Try again in a minute.', 'YOUTUBE')).toBeNull();
    expect(helpForProblem('', 'YOUTUBE')).toBeNull();
    expect(helpForProblem('The platform refused the stream key.', 'TWITCH')).toBeNull();
  });
});
