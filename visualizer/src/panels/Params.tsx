import { useMemo, useState } from 'react';
import { ChevronRight } from 'lucide-react';
import { Hint, SliderRow } from '@/components/fields';
import { PanelSection } from '@/components/tip';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { Input } from '@/components/ui/input';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { command } from '../net/connection';
import type { ParamSpec } from '../protocol/messages';
import { useStore } from '../store/store';
import { formatParam } from './ParamInput';
import { ApplyBadge, ParamRow, useParamEdits } from './ParamRow';

/** Settings tabs by subject: [key, title, matches the group heading after "World · " / "<volcano> · "]. */
export const PARAM_TOPICS: [string, string, RegExp][] = [
  ['weather', 'Weather & scale', /Weather|Scale/],
  ['magma', 'Magma', /Magma/],
  ['eruption', 'Eruption & dikes', /Conduit|Dikes/],
  ['heat', 'Heat & water', /Underground|Hot springs/],
  ['surface', 'Surface', /Pyroclastic|Ash|deformation/i],
  ['other', 'Other', /.*/],
];

/** The settings tab a parameter belongs to. */
export function paramTopic(group: string): string {
  const heading = group.includes(' · ') ? group.slice(group.indexOf(' · ') + 3) : group;
  return PARAM_TOPICS.find(([, , re]) => re.test(heading))?.[0] ?? 'other';
}

/** Parameters page: weather now and every tunable value of the world, in tabs by subject. */
export function Params() {
  const schema = useStore((s) => s.schema);
  const [filter, setFilter] = useState('');
  const [tab, setTab] = useState('weather');
  const { pending, edit } = useParamEdits();

  const specs = schema?.params ?? [];
  const searching = filter.trim() !== '';
  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase();
    return q ? specs.filter((p) => `${p.label} ${p.group} ${p.id} ${p.help ?? ''}`.toLowerCase().includes(q)) : specs;
  }, [specs, filter]);
  const topics = useMemo(() => PARAM_TOPICS.filter(([key]) => key === 'weather' || specs.some((p) => paramTopic(p.group) === key)), [specs]);

  const groupsOf = (list: ParamSpec[]) => [...new Set(list.map((p) => p.group))];
  const renderGroups = (list: ParamSpec[]) =>
    groupsOf(list).map((g) => (
      <section key={g} className="rounded-lg border">
        <h3 className="border-b px-3 py-2 text-sm font-medium">{g}</h3>
        <div className="flex flex-col divide-y">
          {list
            .filter((p) => p.group === g)
            .map((p) => (
              <ParamRow key={p.id} p={p} pending={pending[p.id]} onEdit={edit} />
            ))}
        </div>
      </section>
    ));

  return (
    <div className="flex flex-col gap-4">
      {!schema && <Hint>This server does not publish tunable parameters.</Hint>}
      {schema && !schema.tunable && (
        <Alert>
          <AlertDescription>{schema.reason ?? 'This world cannot be changed.'}</AlertDescription>
        </Alert>
      )}
      <Input type="search" placeholder="Find a setting (e.g. temperature, rain, silica)" aria-label="Find a setting" value={filter} onChange={(e) => setFilter(e.target.value)} />
      {searching ? (
        <div className="flex flex-col gap-3">
          {shown.length === 0 ? <Hint>No setting matches “{filter}”.</Hint> : renderGroups(shown)}
        </div>
      ) : (
        <Tabs value={tab} onValueChange={(v) => setTab(String(v))} className="flex flex-col gap-3">
          <TabsList className="w-full justify-start overflow-x-auto overflow-y-hidden [scrollbar-width:none]" aria-label="Settings topics">
            {topics.map(([key, title]) => (
              <TabsTrigger key={key} value={key} className="min-w-fit flex-none px-2">
                {title}
              </TabsTrigger>
            ))}
          </TabsList>
          {topics.map(([key]) => (
            <TabsContent key={key} value={key} className="flex flex-col gap-3">
              {key === 'weather' && <Weather />}
              {renderGroups(specs.filter((p) => paramTopic(p.group) === key))}
            </TabsContent>
          ))}
        </Tabs>
      )}
      {schema && specs.length > 0 && (
        <Hint>Changes apply as you make them. The server decides how; anything that would reset part of a volcano asks first and says what it does.</Hint>
      )}
      <Audit />
    </div>
  );
}

function Weather() {
  const world = useStore((s) => s.state?.world);
  const [rain, setRain] = useState<number | null>(null);
  const rainNow = world?.rainMmPerHour ?? 0;
  return (
    <PanelSection title="Weather now">
      <SliderRow
        label="Rain"
        help="Rain over the whole map; it soaks into the ground, fills lakes and can start mudflows on ash"
        value={rain ?? rainNow}
        display={`${(rain ?? rainNow).toFixed(0)} mm/h`}
        min={0}
        max={100}
        step={1}
        onChange={setRain}
        onCommit={(v) => {
          command({ kind: 'rain', mmPerHour: v });
          setRain(null);
        }}
      />
      {world?.wind && (
        <Hint>
          Wind {world.wind.speed.toFixed(0)} m/s towards {world.wind.bearingDeg.toFixed(0)}°
        </Hint>
      )}
    </PanelSection>
  );
}

function Audit() {
  const audit = useStore((s) => s.schema?.audit ?? []);
  if (audit.length === 0) return null;
  return (
    <Collapsible className="rounded-lg border">
      <CollapsibleTrigger className="group flex w-full items-center gap-2 px-3 py-2 text-left text-sm font-medium hover:bg-muted/50">
        <ChevronRight className="size-4 transition-transform group-data-[panel-open]:rotate-90" />
        Change history ({audit.length})
      </CollapsibleTrigger>
      <CollapsibleContent>
        <ul className="flex flex-col gap-1 border-t px-3 py-2 text-xs">
          {[...audit].reverse().map((a, k) => (
            <li key={k}>
              <time className="text-muted-foreground tabular-nums">{new Date(a.at).toLocaleTimeString()}</time> {a.label}: {formatParam(a.from)} → {a.to === null ? 'default' : formatParam(a.to)}
              {a.apply !== 'live' && a.apply !== 'hot' && (
                <>
                  {' '}
                  <ApplyBadge kind={a.apply} impact={a.message ? { kind: a.apply === 'restart' ? 'reinit' : a.apply, target: '', message: a.message } : undefined} />
                </>
              )}
            </li>
          ))}
        </ul>
      </CollapsibleContent>
    </Collapsible>
  );
}
