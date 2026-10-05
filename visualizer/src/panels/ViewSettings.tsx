import { Hint, SimpleSelect, SliderRow, SwitchRow } from '@/components/fields';
import { PanelSection, Tip } from '@/components/tip';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
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
    <div className="flex flex-col gap-5">
      <PanelSection title="Map colours">
        <RadioGroup value={s.colorMode} onValueChange={(v) => s.set({ colorMode: v as SurfaceColorMode })} aria-label="Map colours" className="gap-1.5">
          {COLOR_MODES.map(([m, label, title]) => (
            <Tip key={m} content={title} side="left">
              <Label className="flex items-center gap-2 font-normal">
                <RadioGroupItem value={m} /> {label}
              </Label>
            </Tip>
          ))}
        </RadioGroup>
      </PanelSection>
      <PanelSection title="Show">
        <div className="flex flex-col gap-1">
          {LAYERS.map(([k, label, title]) => (
            <SwitchRow key={k} id={`layer-${k}`} label={label} help={title} checked={s[k]} onChange={(v) => s.set({ [k]: v })} />
          ))}
          <SwitchRow id="layer-minimap" label="Minimap" help="Small overhead map; click it to fly somewhere" checked={s.showMinimap} onChange={(v) => s.set({ showMinimap: v })} />
          <SwitchRow
            id="layer-camtools"
            label="All camera tools and position readout"
            help="Follow, tour, saved views, framing and camera settings in the camera bar"
            checked={s.showCameraTools}
            onChange={(v) => s.set({ showCameraTools: v })}
          />
        </div>
      </PanelSection>
      <PanelSection title="Exaggeration">
        <SliderRow
          label="Height"
          help="Stretch heights so gentle slopes are easier to see"
          value={s.verticalExaggeration}
          display={`×${s.verticalExaggeration.toFixed(1)}`}
          min={1}
          max={4}
          step={0.1}
          onChange={(v) => s.set({ verticalExaggeration: v })}
        />
        <SliderRow
          label="Ground swelling"
          help="Magnify ground swelling (centimetres) so it becomes visible"
          value={Math.log10(s.deformationExaggeration)}
          display={`×${s.deformationExaggeration.toFixed(0)}`}
          min={0}
          max={4}
          step={0.1}
          onChange={(v) => s.set({ deformationExaggeration: Math.pow(10, v) })}
        />
      </PanelSection>
      <PanelSection title="Graphics">
        <div className="flex items-center justify-between gap-3">
          <Label htmlFor="quality" className="font-normal">
            Quality
          </Label>
          <SimpleSelect
            id="quality"
            label="Graphics quality"
            value={s.quality}
            onChange={(q: Quality) => {
              rememberQuality(q);
              s.set({ quality: q });
            }}
            options={[
              ['low', 'Low (laptops, integrated graphics)'],
              ['medium', 'Medium'],
              ['high', 'High (shadows, more particles)'],
            ]}
          />
        </div>
        <SwitchRow id="smooth" label="Smooth terrain" help="Smooth the stepped block elevations for display" checked={s.smoothTerrain} onChange={(v) => s.set({ smoothTerrain: v })} />
        <Hint>
          Renderer: {s.renderer}.{' '}
          <Button variant="link" size="xs" className="h-auto p-0" onClick={help}>
            Camera keys and mouse controls
          </Button>
        </Hint>
      </PanelSection>
    </div>
  );
}
