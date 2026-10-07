/** What is still arriving or being built, for the loading pill (null when everything is in). */
export function loadingState(
  status: string,
  hasWorld: boolean,
  expectedTiles: number,
  receivedTiles: number,
  rebuildQueue: number,
): string | null {
  if (status !== 'open') return status === 'connecting' ? 'Connecting to the server…' : 'Connection lost, retrying…';
  if (!hasWorld) return 'Loading the world…';
  if (expectedTiles > 0 && receivedTiles < expectedTiles) {
    return `Loading terrain ${Math.floor((100 * receivedTiles) / expectedTiles)} % (${receivedTiles}/${expectedTiles} tiles)`;
  }
  if (rebuildQueue > 0) return `Building terrain (${rebuildQueue} tile${rebuildQueue === 1 ? '' : 's'} left)`;
  return null;
}
