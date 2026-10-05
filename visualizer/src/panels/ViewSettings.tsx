import { useCamera } from '../camera/cameraStore';
import { rememberQuality, useStore, type Quality, type SurfaceColorMode } from '../store/store';

const COLOR_MODES: [SurfaceColorMode, string, string][] = [
  ['natural', 'Natural', 'Rock, vegetation, lava and water as they would look'],
  ['surfaceTemperature', 'Ground temperature', 'Hot ground glows; shows lava and heated areas'],
  ['waterTable', 'Groundwater depth', 'How far below the surface the water table is'],
  ['topUnit', 'Rock layers', 'Which deposit is at the surface (each eruption has its own colour)'],
  ['uplift', 'Ground swelling', 'Uplift (red) and subsidence (blue) since the start'],
  ['ash', 'Ash thickness', 'Fallen ash and tephra'],
  ['steam', 'Steam', 'Where the ground is steaming'],
];

const LAYERS = [
  ['showHypocentres', 'Earthquakes', 'Recent quake locations as dots below the surface'],
  ['showChambers', 'Magma chambers', 'The magma reservoir and conduits underground'],
  ['showAtmosphere', 'Plumes and gas', 'Eruption columns, ash and gas clouds'],
  ['showFeatures', 'Vents and springs', 'Vents, fumaroles, hot springs and geysers'],
  ['showWaterTable', 'Water table', 'The groundwater surface as a translucent sheet'],
  ['xray', 'See-through ground when underground', 'Make the ground translucent while the camera is below it'],
] as const;

/** View page: what the map shows, exaggeration, graphics quality and camera extras. */
export function ViewSettings() {
  const s = useStore();
  const help = () => useCamera.getState().set({ helpOpen: true });
  return (
    <div className="view-settings">
      <section>
        <h3>Map colours</h3>
        <div className="radio-list" role="radiogroup" aria-label="Map colours">
          {COLOR_MODES.map(([m, label, title]) => (
            <label key={m} title={title} className="check">
              <input type="radio" name="colorMode" checked={s.colorMode === m} onChange={() => s.set({ colorMode: m })} /> {label}
            </label>
          ))}
        </div>
      </section>
      <section>
        <h3>Show</h3>
        {LAYERS.map(([k, label, title]) => (
          <label key={k} className="check" title={title}>
            <input type="checkbox" checked={s[k]} onChange={(e) => s.set({ [k]: e.target.checked })} /> {label}
          </label>
        ))}
        <label className="check" title="Small overhead map; click it to fly somewhere">
          <input type="checkbox" checked={s.showMinimap} onChange={(e) => s.set({ showMinimap: e.target.checked })} /> Minimap
        </label>
        <label className="check" title="Follow, tour, saved views, framing and camera settings in the camera bar">
          <input type="checkbox" checked={s.showCameraTools} onChange={(e) => s.set({ showCameraTools: e.target.checked })} /> All camera tools and position readout
        </label>
      </section>
      <section>
        <h3>Exaggeration</h3>
        <label className="slider" title="Stretch heights so gentle slopes are easier to see">
          <span>Height ×{s.verticalExaggeration.toFixed(1)}</span>
          <input type="range" min={1} max={4} step={0.1} value={s.verticalExaggeration} onChange={(e) => s.set({ verticalExaggeration: Number(e.target.value) })} />
        </label>
        <label className="slider" title="Magnify ground swelling (centimetres) so it becomes visible">
          <span>Ground swelling ×{s.deformationExaggeration.toFixed(0)}</span>
          <input type="range" min={0} max={4} step={0.1} value={Math.log10(s.deformationExaggeration)} onChange={(e) => s.set({ deformationExaggeration: Math.pow(10, Number(e.target.value)) })} />
        </label>
      </section>
      <section>
        <h3>Graphics</h3>
        <label className="slider">
          <span>Quality</span>
          <select
            value={s.quality}
            onChange={(e) => {
              const q = e.target.value as Quality;
              rememberQuality(q);
              s.set({ quality: q });
            }}
          >
            <option value="low">Low (laptops, integrated graphics)</option>
            <option value="medium">Medium</option>
            <option value="high">High (shadows, more particles)</option>
          </select>
        </label>
        <label className="check" title="Smooth the stepped block elevations for display">
          <input type="checkbox" checked={s.smoothTerrain} onChange={(e) => s.set({ smoothTerrain: e.target.checked })} /> Smooth terrain
        </label>
        <p className="muted small">
          Renderer: {s.renderer}.{' '}
          <button className="link" onClick={help}>
            Camera keys and mouse controls
          </button>
        </p>
      </section>
    </div>
  );
}
