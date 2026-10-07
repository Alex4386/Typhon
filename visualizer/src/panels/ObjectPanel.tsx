import { useState, type ReactNode } from 'react';
import { ChevronRight, Loader2, Pin, RotateCcw } from 'lucide-react';
import { Tip } from '@/components/tip';
import { Button } from '@/components/ui/button';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { cn } from '@/lib/utils';
import type { EntityProp, PanelField, ParamSpec, ParamValue } from '../protocol/messages';
import { loadPref, savePref } from '../store/prefs';
import { useStore } from '../store/store';
import { buildPanel, fieldShown, pinParam, type BuiltPanel, type BuiltTab } from './objectPanel';
import { formatParam } from './ParamInput';
import { ParamRow } from './ParamRow';
import { atRest } from './paramState';
import { formatProp } from './props';

/** Built-in views a panel field can name (rendered by the Inspector, which knows the selection). */
export type WidgetRenderer = (id: string) => ReactNode;

const isBool = (v: unknown): v is boolean => typeof v === 'boolean';

/** A collapsible group whose open state is remembered per object kind and tab (per browser). */
function Remembered({ prefKey, title, count, children, className }: { prefKey: string; title: string; count: number; children: ReactNode; className?: string }) {
  const key = `typhon.inspector.${prefKey}`;
  const [open, setOpen] = useState(() => loadPref(key, false, isBool));
  return (
    <Collapsible
      open={open}
      onOpenChange={(o) => {
        setOpen(o);
        savePref(key, o);
      }}
      className={cn('rounded-md border', className)}
    >
      <CollapsibleTrigger className="group flex w-full items-center gap-1.5 px-2 py-1.5 text-left text-xs font-medium text-muted-foreground hover:bg-muted/50">
        <ChevronRight className="size-3.5 transition-transform group-data-[panel-open]:rotate-90" />
        {title}
        <span className="ml-auto tabular-nums">{count}</span>
      </CollapsibleTrigger>
      <CollapsibleContent className="flex flex-col divide-y border-t px-2">{children}</CollapsibleContent>
    </Collapsible>
  );
}

/** A measured or derived value, read-only; a derived one says so and offers its pin. */
function FieldRow({ f, props, owners, onTab, onEdit }: { f: PanelField; props?: Record<string, EntityProp>; owners: string[]; onTab?: (tab: string) => void; onEdit: (p: ParamSpec, v: ParamValue | null) => void }) {
  const schema = useStore((s) => s.schema);
  const v = f.measure ? props?.[f.measure] : undefined;
  if (v === undefined || v === null || !f.measure) return null;
  const text = typeof v === 'number' ? `${formatParam(v)}${f.unit ? ` ${f.unit}` : ''}` : formatProp(f.measure, v);
  const pin = pinParam(schema, owners, f);
  const pinned = pin ? !atRest(pin, undefined) : false;
  return (
    <div className="contents">
      <dt className="flex items-center gap-1 text-muted-foreground">
        <Tip content={f.help ?? f.label ?? f.measure}>
          <span>{f.label ?? f.measure}</span>
        </Tip>
        {f.derived && (
          <Tip content={pinned ? `Pinned by “${pin!.label}” (${formatParam(pin!.value, pin)}); the physics would compute its own value` : 'Computed by the physics from the settings; updates live'}>
            <span className={cn('rounded px-1 text-[10px]', pinned ? 'bg-amber-500/15 text-amber-300' : 'bg-muted text-muted-foreground')}>{pinned ? 'pinned' : 'computed'}</span>
          </Tip>
        )}
      </dt>
      <dd className="flex items-center justify-end gap-1 text-right tabular-nums">
        {text}
        {pin && pinned && <PinReset p={pin} onEdit={onEdit} />}
        {pin && !pinned && onTab && (
          <Tip content={`Pin it: “${pin.label}” under Overrides`}>
            <Button variant="ghost" size="icon-xs" aria-label={`Pin ${f.label}`} onClick={() => onTab(pin.tab ?? 'overrides')}>
              <Pin />
            </Button>
          </Tip>
        )}
      </dd>
    </div>
  );
}

/** Hands a pinned value back to the physics. */
function PinReset({ p, onEdit }: { p: ParamSpec; onEdit: (p: ParamSpec, v: ParamValue | null) => void }) {
  return (
    <Tip content={`Unpin: back to ${p.auto ? 'the computed value' : `the default (${formatParam(p.default, p)})`}`}>
      <Button variant="ghost" size="icon-xs" aria-label={`Unpin ${p.label}`} onClick={() => onEdit(p, null)}>
        <RotateCcw />
      </Button>
    </Tip>
  );
}

/**
 * One tab of an object's panel: the server's sections (measured and derived values, widgets), then
 * the tab's dials, then "More" and "Solver internals", collapsed.
 */
export function PanelTabBody({
  tab,
  kind,
  owners,
  props,
  widget,
  pending,
  onEdit,
  onTab,
}: {
  tab: BuiltTab;
  kind: string;
  owners: string[];
  props?: Record<string, EntityProp>;
  widget: WidgetRenderer;
  pending: Record<string, ParamValue | null>;
  onEdit: (p: ParamSpec, v: ParamValue | null) => void;
  onTab?: (tab: string) => void;
}) {
  const { primary, more, internals } = tab.params;
  return (
    <>
      {tab.sections.map((s, k) => {
        const fields = s.fields.filter((f) => fieldShown(f, props));
        if (fields.length === 0) return null;
        const values = fields.filter((f) => f.measure);
        return (
          <section key={k} className="flex flex-col gap-1.5">
            {s.title && <h3 className="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{s.title}</h3>}
            {fields.filter((f) => f.widget).map((f) => (
              <div key={f.widget}>{widget(f.widget!)}</div>
            ))}
            {values.length > 0 && (
              <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs" aria-label={s.title ?? tab.title}>
                {values.map((f) => (
                  <FieldRow key={f.measure} f={f} props={props} owners={owners} onTab={onTab} onEdit={onEdit} />
                ))}
              </dl>
            )}
          </section>
        );
      })}
      {primary.length > 0 && (
        <section className="flex flex-col divide-y" aria-label={`${tab.title} settings`}>
          {primary.map((p) => (
            <ParamRow key={p.id} p={p} pending={pending[p.id]} onEdit={onEdit} compact />
          ))}
        </section>
      )}
      {more.length > 0 && (
        <Remembered prefKey={`more.${kind}.${tab.id}`} title="More" count={more.length}>
          {more.map((p) => (
            <ParamRow key={p.id} p={p} pending={pending[p.id]} onEdit={onEdit} compact />
          ))}
        </Remembered>
      )}
      {internals.length > 0 && (
        <Remembered prefKey={`internals.${kind}.${tab.id}`} title="Solver internals" count={internals.length} className="border-dashed">
          {internals.map((p) => (
            <ParamRow key={p.id} p={p} pending={pending[p.id]} onEdit={onEdit} compact />
          ))}
        </Remembered>
      )}
    </>
  );
}

/** The object's panel from the schema, or null while the schema has not arrived. */
export function usePanel(kind: string, owners: string[], props?: Record<string, EntityProp>): BuiltPanel | null {
  const schema = useStore((s) => s.schema);
  if (!schema) return null;
  return buildPanel(schema, kind, owners, props);
}

/** "Overrides active" banner: the physics is overridden on this object, with a way to the tab. */
export function OverridesBanner({ panel, onTab }: { panel: BuiltPanel; onTab: (tab: string) => void }) {
  if (panel.overrides.length === 0) return null;
  return (
    <button
      type="button"
      className="mx-3 mb-2 shrink-0 rounded-md border border-amber-500/40 bg-amber-500/10 px-2 py-1.5 text-left text-xs text-amber-200 hover:bg-amber-500/15"
      onClick={() => onTab('overrides')}
    >
      <span className="font-medium">Physics overridden:</span> {panel.overrides.map((p) => `${p.label} ${formatParam(p.value, p)}`).join(', ')}
    </button>
  );
}

/** Placeholder while server data is on its way. */
export function Waiting({ children }: { children: ReactNode }) {
  return (
    <p className="flex items-center gap-2 text-xs text-muted-foreground" role="status">
      <Loader2 className="size-3.5 animate-spin" aria-hidden />
      {children}
    </p>
  );
}

/** Grey bars standing in for rows that are loading. */
export function Skeleton({ rows = 3 }: { rows?: number }) {
  return (
    <div className="flex flex-col gap-2" aria-hidden>
      {Array.from({ length: rows }, (_, k) => (
        <div key={k} className="h-3 animate-pulse rounded bg-muted" style={{ width: `${90 - k * 15}%` }} />
      ))}
    </div>
  );
}
