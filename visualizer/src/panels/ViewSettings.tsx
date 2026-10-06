import {
  Hint,
  SimpleSelect,
  SliderRow,
  CheckboxRow,
} from "@/components/fields";
import { PanelSection, Tip } from "@/components/tip";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { useCamera } from "../camera/cameraStore";
import type { QuakeFilter } from "../store/quakeFilter";
import {
  rememberQuality,
  useStore,
  type Quality,
  type SurfaceColorMode,
} from "../store/store";

const COLOR_MODES: [SurfaceColorMode, string, string][] = [
  ["natural", "Natural", "Rock, vegetation, lava and water as they would look"],
  [
    "surfaceTemperature",
    "Ground temperature",
    "Hot ground glows; shows lava and heated areas",
  ],
  [
    "waterTable",
    "Groundwater depth",
    "How far below the surface the water table is",
  ],
  [
    "topUnit",
    "Rock layers",
    "Which deposit is at the surface (each eruption has its own colour)",
  ],
  [
    "uplift",
    "Ground swelling",
    "Uplift (red) and subsidence (blue) since the start",
  ],
  ["ash", "Ash thickness", "Fallen ash and tephra"],
  ["steam", "Steam", "Where the ground is steaming"],
];

const LAYERS = [
  [
    "showHypocentres",
    "Earthquakes",
    "Recent quake locations as dots below the surface",
  ],
  [
    "showChambers",
    "Magma chambers",
    "The magma reservoir and conduits underground",
  ],
  ["showAtmosphere", "Plumes and gas", "Eruption columns, ash and gas clouds"],
  [
    "showFeatures",
    "Vents and springs",
    "Vents, fumaroles, hot springs and geysers",
  ],
  [
    "showWaterTable",
    "Water table",
    "The groundwater surface as a translucent sheet",
  ],
  [
    "showSimulatedArea",
    "Simulated area",
    "Outline of the ground being simulated; beyond it the landscape is generated and becomes simulated when lava, flows, ash or water reach it",
  ],
  [
    "xray",
    "See-through ground when underground",
    "Make the ground translucent while the camera is below it",
  ],
] as const;

/** View page in tabs: Map (colours), Layers (what is drawn, earthquakes), Graphics (exaggeration, quality). */
export function ViewSettings() {
  const s = useStore();
  const help = () => useCamera.getState().set({ helpOpen: true });
  return (
    <Tabs defaultValue="map" className="flex flex-col gap-4">
      <TabsList className="w-full" aria-label="View settings">
        <TabsTrigger value="map">Map</TabsTrigger>
        <TabsTrigger value="layers">Layers</TabsTrigger>
        <TabsTrigger value="graphics">Graphics</TabsTrigger>
      </TabsList>
      <TabsContent value="map" className="flex flex-col gap-5">
        <PanelSection title="Map colours">
          <RadioGroup
            value={s.colorMode}
            onValueChange={(v) => s.set({ colorMode: v as SurfaceColorMode })}
            aria-label="Map colours"
            className="gap-1.5"
          >
            {COLOR_MODES.map(([m, label, title]) => (
              <Tip key={m} content={title} side="left">
                <Label className="flex items-center gap-2 font-normal">
                  <RadioGroupItem value={m} /> {label}
                </Label>
              </Tip>
            ))}
          </RadioGroup>
        </PanelSection>
      </TabsContent>
      <TabsContent value="layers" className="flex flex-col gap-5">
        <PanelSection title="Show">
          <div className="flex flex-col gap-1">
            {LAYERS.map(([k, label, title]) => (
              <CheckboxRow
                key={k}
                id={`layer-${k}`}
                label={label}
                help={title}
                checked={s[k]}
                onChange={(v) => s.set({ [k]: v })}
              />
            ))}
            <CheckboxRow
              id="layer-minimap"
              label="Minimap"
              help="Small overhead map; click it to fly somewhere"
              checked={s.showMinimap}
              onChange={(v) => s.set({ showMinimap: v })}
            />
            <CheckboxRow
              id="layer-camtools"
              label="All camera tools and position readout"
              help="Follow, tour, saved views, framing and camera settings in the camera bar"
              checked={s.showCameraTools}
              onChange={(v) => s.set({ showCameraTools: v })}
            />
          </div>
        </PanelSection>
        <QuakeSettings />
      </TabsContent>
      <TabsContent value="graphics" className="flex flex-col gap-5">
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
            onChange={(v) =>
              s.set({ deformationExaggeration: Math.pow(10, v) })
            }
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
                ["low", "Low (laptops, integrated graphics)"],
                ["medium", "Medium"],
                ["high", "High (shadows, more particles)"],
              ]}
            />
          </div>
          <CheckboxRow
            id="smooth"
            label="Smooth terrain"
            help="Smooth the stepped block elevations for display"
            checked={s.smoothTerrain}
            onChange={(v) => s.set({ smoothTerrain: v })}
          />
          <CheckboxRow
            id="auto-quality"
            label="Adapt resolution to keep it smooth"
            help="Render at a lower resolution while frames fall behind, and back up when there is headroom"
            checked={s.autoQuality}
            onChange={(v) => s.set({ autoQuality: v })}
          />
          <CheckboxRow
            id="perf-hud"
            label="Frame statistics"
            help="Frames per second, frame and script time, draw calls, triangles and markers drawn, over the view"
            checked={s.showPerf}
            onChange={(v) => s.set({ showPerf: v })}
          />
          <Hint>
            Renderer: {s.renderer}.{" "}
            <Button
              variant="link"
              size="xs"
              className="h-auto p-0"
              onClick={help}
            >
              Camera keys and mouse controls
            </Button>
          </Hint>
        </PanelSection>
      </TabsContent>
    </Tabs>
  );
}

const QUAKE_COUNTS = [0, 10, 20, 50, 100, 200, 500, 1000, 2000];
const QUAKE_WINDOWS: [string, string][] = [
  ["off", "Any time"],
  ["600", "Last 10 minutes"],
  ["3600", "Last hour"],
  ["21600", "Last 6 hours"],
  ["86400", "Last day"],
  ["604800", "Last week"],
];
const QUAKE_MAGS: [string, string][] = [
  ["off", "All"],
  ["1", "M 1 and up"],
  ["2", "M 2 and up"],
  ["3", "M 3 and up"],
  ["4", "M 4 and up"],
];

/** How many earthquakes the view, the Entities panel and the event lists show (last N as of now). */
function QuakeSettings() {
  const f = useStore((s) => s.quakeFilter);
  const set = useStore((s) => s.set);
  const put = (o: Partial<QuakeFilter>) => set({ quakeFilter: { ...f, ...o } });
  const idx = QUAKE_COUNTS.reduce(
    (best, c, k) =>
      Math.abs(c - f.count) < Math.abs(QUAKE_COUNTS[best] - f.count) ? k : best,
    0,
  );
  return (
    <PanelSection title="Earthquakes">
      <SliderRow
        label="Show the last"
        help="Only the most recent earthquakes as of the current time (in a replay, as of the replay cursor)"
        value={idx}
        display={f.count === 0 ? "none" : `${f.count} quakes`}
        min={0}
        max={QUAKE_COUNTS.length - 1}
        step={1}
        onChange={(v) => put({ count: QUAKE_COUNTS[v] })}
      />
      <div className="flex items-center justify-between gap-3">
        <Label htmlFor="quake-window" className="font-normal">
          Within
        </Label>
        <SimpleSelect
          id="quake-window"
          label="Time window"
          value={f.windowS == null ? "off" : String(f.windowS)}
          onChange={(v) => put({ windowS: v === "off" ? null : Number(v) })}
          options={QUAKE_WINDOWS}
        />
      </div>
      <div className="flex items-center justify-between gap-3">
        <Label htmlFor="quake-mag" className="font-normal">
          Magnitude
        </Label>
        <SimpleSelect
          id="quake-mag"
          label="Minimum magnitude"
          value={f.minMagnitude == null ? "off" : String(f.minMagnitude)}
          onChange={(v) =>
            put({ minMagnitude: v === "off" ? null : Number(v) })
          }
          options={QUAKE_MAGS}
        />
      </div>
      <CheckboxRow
        id="quake-fade"
        label="Fade older quakes"
        help="Older quakes are drawn dimmer"
        checked={f.fade}
        onChange={(v) => put({ fade: v })}
      />
    </PanelSection>
  );
}
