import { useMemo, useState } from 'react';
import { ArrowRight, ChevronRight } from 'lucide-react';
import { Hint } from '@/components/fields';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { Input } from '@/components/ui/input';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useStore } from '../store/store';
import { buildPanel, ownerEntity } from './panelSpec';
import { PanelTabBody, Skeleton, Waiting } from './ObjectPanel';
import { formatParam } from './ParamInput';
import { ApplyBadge, ParamRow, useParamEdits } from './ParamRow';

/**
 * The Settings drawer: the world's own settings (weather, the underground, the map), laid out like
 * the world's Inspector panel, plus links to each volcano's settings, which live in the Inspector of
 * the object they describe. A search finds every setting and leads to where it lives.
 */
export function Params() {
  const schema = useStore((s) => s.schema);
  const entities = useStore((s) => s.entities);
  const [filter, setFilter] = useState('');
  const [tab, setTab] = useState('weather');
  const { pending, edit } = useParamEdits();

  const specs = schema?.params ?? [];
  const searching = filter.trim() !== '';
  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase();
    return q ? specs.filter((p) => `${p.label} ${p.group} ${p.id} ${p.help ?? ''}`.toLowerCase().includes(q)) : [];
  }, [specs, filter]);
  const owners = schema?.objectOwners?.world ?? entities.world?.paramOwners ?? ['world'];
  const panel = schema ? buildPanel(schema, 'world', owners, entities.world?.props) : null;
  const volcanoes = Object.values(entities).filter((e) => e.kind === 'volcano' && e.removedAt === undefined);
  const inspect = (id: string) => useStore.getState().select({ type: 'entity', id });

  return (
    <div className="flex flex-col gap-4">
      {!schema && (
        <>
          <Waiting>Loading the settings…</Waiting>
          <Skeleton rows={6} />
        </>
      )}
      {schema && !schema.tunable && (
        <Alert>
          <AlertDescription>{schema.reason ?? 'This world cannot be changed.'}</AlertDescription>
        </Alert>
      )}
      <Input type="search" placeholder="Find any setting (e.g. temperature, rain, silica)" aria-label="Find a setting" value={filter} onChange={(e) => setFilter(e.target.value)} />
      {searching ? (
        <div className="flex flex-col divide-y rounded-lg border">
          {shown.length === 0 ? (
            <Hint className="p-3">No setting matches “{filter}”.</Hint>
          ) : (
            shown.map((p) => {
              if (p.owner === 'world' || !p.volcanoId) return <ParamRow key={p.id} p={p} pending={pending[p.id]} onEdit={edit} />;
              const target = ownerEntity(entities, p);
              return (
                <div key={p.id} className="flex items-center gap-2 px-3 py-2 text-sm">
                  <div className="min-w-0 flex-1">
                    <div className="truncate">{p.label}</div>
                    <div className="truncate text-xs text-muted-foreground">
                      {p.group} · {formatParam(p.value, p)}
                    </div>
                  </div>
                  <Button size="xs" variant="secondary" disabled={!target} onClick={() => target && inspect(target)}>
                    Edit in Inspector <ArrowRight />
                  </Button>
                </div>
              );
            })
          )}
        </div>
      ) : (
        <>
          {volcanoes.length > 0 && (
            <section className="flex flex-col gap-1.5 rounded-lg border p-3">
              <h3 className="text-sm font-medium">Volcanoes</h3>
              <Hint>Each volcano’s settings are in the Inspector of the object they describe: the volcano, its chambers, pathways and vents.</Hint>
              <div className="flex flex-wrap gap-1.5">
                {volcanoes.map((v) => (
                  <Button key={v.id} size="xs" variant="secondary" onClick={() => inspect(v.id)}>
                    {v.label} <ArrowRight />
                  </Button>
                ))}
                {volcanoes.map((v) =>
                  entities[`chamber:${v.volcanoId}`] ? (
                    <Button key={`c-${v.id}`} size="xs" variant="outline" onClick={() => inspect(`chamber:${v.volcanoId}`)}>
                      {entities[`chamber:${v.volcanoId}`].label} <ArrowRight />
                    </Button>
                  ) : null,
                )}
              </div>
            </section>
          )}
          {panel && panel.tabs.length > 0 && (
            <Tabs value={panel.tabs.some((t) => t.id === tab) ? tab : panel.tabs[0].id} onValueChange={(v) => setTab(String(v))} className="flex flex-col gap-3">
              <TabsList className="w-full justify-start overflow-x-auto overflow-y-hidden [scrollbar-width:none]" aria-label="World settings">
                {panel.tabs.map((t) => (
                  <TabsTrigger key={t.id} value={t.id} className="min-w-fit flex-none px-2">
                    {t.title}
                  </TabsTrigger>
                ))}
              </TabsList>
              {panel.tabs.map((t) => (
                <TabsContent key={t.id} value={t.id} className="flex flex-col gap-3">
                  <PanelTabBody tab={t} kind="world" owners={owners} props={entities.world?.props} widget={(id) => (id === 'weatherNow' ? <WeatherNow /> : null)} pending={pending} onEdit={edit} />
                </TabsContent>
              ))}
            </Tabs>
          )}
        </>
      )}
      {schema && specs.length > 0 && (
        <Hint>Changes apply as you make them. The server decides how; anything that would reset part of a volcano asks first and says what it does.</Hint>
      )}
      <Audit />
    </div>
  );
}

function WeatherNow() {
  const world = useStore((s) => s.state?.world);
  if (!world) return <Skeleton rows={2} />;
  return (
    <Hint>
      Now: rain {(world.rainMmPerHour ?? 0).toFixed(0)} mm/h
      {world.wind && `, wind ${world.wind.speed.toFixed(0)} m/s towards ${world.wind.bearingDeg.toFixed(0)}°`}
    </Hint>
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
