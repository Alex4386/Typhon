import { useEffect, useState } from "react";
import { ChevronRight, Eye, Pause, Play, Power, Trash2 } from "lucide-react";
import { Hint, SimpleSelect } from "@/components/fields";
import { PanelSection, Tip } from "@/components/tip";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { cn } from "@/lib/utils";
import {
  attachSession,
  controlSession,
  createSession,
  deleteWorld,
  refreshCatalog,
  send,
} from "../net/connection";
import type { ParamValue } from "../protocol/messages";
import { fieldError } from "./inject";
import { ParamInput } from "./ParamInput";
import type { SessionInfo } from "../protocol/messages";
import { useStore } from "../store/store";
import { ALERT_COLORS } from "../util/color";
import { formatFactor, formatSimTime } from "../util/world";
import { ALERT_LABEL } from "./events";
import { sessionLabel } from "./Header";

/** A confirmation step for something that cannot be undone. */
interface Confirm {
  title: string;
  body: string;
  action: string;
  run: () => void;
}

/** Worlds page: what the server runs, what it can open, and starting new worlds. */
export function SessionManager() {
  const sessions = useStore((s) => s.sessions);
  const sessionId = useStore((s) => s.sessionId);
  const catalog = useStore((s) => s.catalog);
  const server = useStore((s) => s.serverInfo);
  const [confirm, setConfirm] = useState<Confirm | null>(null);
  useEffect(() => refreshCatalog(), []);
  const full = server ? sessions.length >= server.maxSessions : false;
  const closed = (catalog?.worlds ?? []).filter((w) => !w.sessionId);
  return (
    <div className="flex flex-col gap-5">
      <PanelSection title="Running now">
        {sessions.length === 0 && (
          <Hint>Nothing is running. Start a world below.</Hint>
        )}
        <ul className="flex flex-col gap-2">
          {sessions.map((s) => (
            <SessionRow
              key={s.id}
              s={s}
              attached={s.id === sessionId}
              onConfirm={setConfirm}
            />
          ))}
        </ul>
        {server && (
          <Tip content="Every running world shares the server's processors and memory; paused worlds use memory but no processor time.">
            <Hint>
              {sessions.length} of {server.maxSessions} worlds · memory{" "}
              {server.heapUsedMB} / {server.heapMaxMB} MB · {server.cpus}{" "}
              processors
            </Hint>
          </Tip>
        )}
      </PanelSection>
      <NewWorld disabled={full} />
      <PanelSection title="Saved worlds">
        {!server?.worldsDir && (
          <Hint>
            The server has no worlds folder (start it with --worlds-dir).
          </Hint>
        )}
        {server?.worldsDir && closed.length === 0 && (
          <Hint>No other saved worlds in {server.worldsDir}.</Hint>
        )}
        <ul className="flex flex-col gap-2">
          {closed.map((w) => (
            <li
              key={w.name}
              className="flex items-center gap-2 rounded-lg border p-2.5"
            >
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium">
                  {w.title || w.name}
                </div>
                <div className="text-xs text-muted-foreground">
                  {w.error ? (
                    <span className="text-destructive">{w.error}</span>
                  ) : (
                    `${w.volcanoes} volcano${w.volcanoes === 1 ? "" : "es"} · ${w.hasState ? "saved progress" : "not started yet"}`
                  )}
                </div>
              </div>
              <Tip
                content={
                  full
                    ? "The server runs as many worlds as it may; close one first"
                    : "Load this world and watch it"
                }
              >
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={full || !!w.error}
                  onClick={() => createSession({ world: w.name })}
                >
                  Open
                </Button>
              </Tip>
              <Tip content="Delete this world's folder, including its saved progress and replay">
                <Button
                  size="icon-sm"
                  variant="ghost"
                  aria-label={`Delete ${w.name}`}
                  onClick={() =>
                    setConfirm({
                      title: `Delete “${w.name}”?`,
                      body: "The world folder, its saved progress and its replay are deleted. This cannot be undone.",
                      action: "Delete",
                      run: () => deleteWorld(w.name),
                    })
                  }
                >
                  <Trash2 />
                </Button>
              </Tip>
            </li>
          ))}
        </ul>
      </PanelSection>
      <Dialog
        open={confirm !== null}
        onOpenChange={(o) => !o && setConfirm(null)}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{confirm?.title}</DialogTitle>
            <DialogDescription>{confirm?.body}</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <DialogClose render={<Button variant="outline" />}>
              Cancel
            </DialogClose>
            <Button
              variant="destructive"
              onClick={() => {
                confirm?.run();
                setConfirm(null);
              }}
            >
              {confirm?.action}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

const ORDER = [
  "EXTINCT",
  "DORMANT",
  "MINOR_ACTIVITY",
  "MAJOR_ACTIVITY",
  "ERUPTION_IMMINENT",
  "ERUPTING",
];

function SessionRow({
  s,
  attached,
  onConfirm,
}: {
  s: SessionInfo;
  attached: boolean;
  onConfirm: (c: Confirm) => void;
}) {
  const paused = s.mode === "PAUSED";
  const top = s.volcanoes?.reduce<string | null>(
    (best, v) =>
      best === null || ORDER.indexOf(v.alert) > ORDER.indexOf(best)
        ? v.alert
        : best,
    null,
  );
  const close = () => controlSession(s.id, "close");
  return (
    <li
      className={cn(
        "flex items-center gap-2 rounded-lg border p-2.5",
        attached && "border-primary/60 bg-primary/5",
      )}
    >
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-1.5">
          <span className="truncate text-sm font-medium" title={s.name}>
            {sessionLabel(s)}
          </span>
          {attached && <Badge variant="secondary">watching</Badge>}
          {top && (
            <Badge
              className="text-white"
              style={{ background: ALERT_COLORS[top] ?? "#444" }}
            >
              {ALERT_LABEL[top] ?? top}
            </Badge>
          )}
        </div>
        <div className="text-xs text-muted-foreground">
          {paused
            ? "Paused"
            : s.mode === "UNBOUNDED"
              ? "Running flat out"
              : `Running ${formatFactor(s.speed ?? 0)}`}
          {s.playback?.slowed ? ` · slowed for the ${s.playback.slowedBy === "eruption" ? "eruption" : "event"}` : ""}
          {" "}· {formatSimTime(s.time, false)}
          {s.clients ? ` · ${s.clients} viewer${s.clients > 1 ? "s" : ""}` : ""}
          {!s.world && " · not saved to disk"}
        </div>
      </div>
      {!attached && (
        <Button size="sm" onClick={() => attachSession(s.id)}>
          <Eye /> Watch
        </Button>
      )}
      <Tip
        content={
          paused
            ? "Resume this world"
            : "Pause this world (it keeps its memory but stops computing)"
        }
      >
        <Button
          size="icon-sm"
          variant="ghost"
          aria-label={paused ? "Resume" : "Pause"}
          onClick={() =>
            attached
              ? send(
                  paused
                    ? {
                        type: "transport",
                        mode: "REALTIME",
                        speed: s.speed ?? 20,
                      }
                    : { type: "transport", mode: "PAUSED" },
                )
              : controlSession(s.id, paused ? "resume" : "pause")
          }
        >
          {paused ? <Play /> : <Pause />}
        </Button>
      </Tip>
      <Tip
        content={
          s.world
            ? "Save and unload this world (open it again from Saved worlds)"
            : "Unload this world; it was never saved, so it is gone afterwards"
        }
      >
        <Button
          size="icon-sm"
          variant="ghost"
          aria-label="Close"
          onClick={() =>
            s.world
              ? close()
              : onConfirm({
                  title: `Close “${sessionLabel(s)}”?`,
                  body: "This world only lives in memory; closing it loses it.",
                  action: "Close and lose it",
                  run: close,
                })
          }
        >
          <Power />
        </Button>
      </Tip>
    </li>
  );
}

function NewWorld({ disabled }: { disabled: boolean }) {
  const catalog = useStore((s) => s.catalog);
  const presets = catalog?.presets ?? [];
  const templates = catalog?.templates ?? [];
  const [preset, setPreset] = useState("");
  const [params, setParams] = useState<Record<string, ParamValue>>({});
  const [name, setName] = useState("");
  const [paused, setPaused] = useState(false);
  // empty worlds (templates) first: the world-builder path; ready-made presets after
  const fallback = templates.length
    ? `t:${templates.find((t) => t.name === catalog?.defaultTemplate)?.name ?? templates[0].name}`
    : `p:${presets.find((p) => p.name === catalog?.defaultPreset)?.name ?? presets[0]?.name}`;
  const choice = preset || fallback;
  const template = choice.startsWith("t:")
    ? templates.find((t) => `t:${t.name}` === choice)
    : undefined;
  const chosen = choice.startsWith("p:")
    ? presets.find((p) => `p:${p.name}` === choice)
    : undefined;
  const paramErrors = (template?.fields ?? []).map((f) =>
    params[f.id] !== undefined ? fieldError(f, params[f.id]) : null,
  );
  const nameOk = name === "" || /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(name);
  return (
    <PanelSection title="Start a new world">
      <form
        className="flex flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (
            (!chosen && !template) ||
            !nameOk ||
            paramErrors.some((x) => x !== null)
          )
            return;
          if (template) {
            const sent: Record<string, number> = {};
            for (const [k, v] of Object.entries(params))
              if (typeof v === "number") sent[k] = v;
            createSession({
              template: template.name,
              params: sent,
              name: name || undefined,
              paused,
            });
          } else if (chosen) {
            createSession({
              preset: chosen.name,
              name: name || undefined,
              paused,
            });
          }
          setName("");
          setParams({});
        }}
      >
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="nw-preset">Start from</Label>
          <SimpleSelect
            id="nw-preset"
            label="Start from"
            size="default"
            className="w-full"
            value={choice}
            onChange={(v) => {
              setPreset(v);
              setParams({});
            }}
            options={[
              ...templates.map(
                (t) => [`t:${t.name}`, `Empty: ${t.title}`] as const,
              ),
              ...presets.map(
                (p) =>
                  [
                    `p:${p.name}`,
                    `Scenario: ${p.title || p.name}${p.realScale ? " (real scale)" : ""}`,
                  ] as const,
              ),
            ]}
          />
          {(template ?? chosen)?.description && (
            <Hint>{(template ?? chosen)?.description}</Hint>
          )}
        </div>
        {template && (
          <div className="flex flex-col gap-2 rounded-lg border p-3">
            {template.fields.map((f, k) => (
              <div key={f.id} className="flex flex-col gap-1">
                <Label
                  htmlFor={`p-${f.id}`}
                  className="font-normal"
                  title={f.help}
                >
                  {f.label}
                  {f.unit ? ` (${f.unit})` : ""}
                </Label>
                <ParamInput
                  spec={f}
                  value={params[f.id] ?? f.default}
                  invalid={paramErrors[k] !== null}
                  onChange={(v) => setParams((x) => ({ ...x, [f.id]: v }))}
                />
                {paramErrors[k] && (
                  <p className="text-xs text-destructive">{paramErrors[k]}</p>
                )}
              </div>
            ))}
            <Hint>
              Nothing is placed: once it runs, use “Place magma chamber” on the
              map.
            </Hint>
          </div>
        )}
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="nw-name">Name</Label>
          <Input
            id="nw-name"
            placeholder={
              template || chosen
                ? `${(template ?? chosen)!.name} (automatic)`
                : ""
            }
            value={name}
            aria-invalid={!nameOk}
            onChange={(e) => setName(e.target.value)}
          />
          {!nameOk && (
            <p className="text-xs text-destructive">
              Letters, digits, dot, dash and underscore only.
            </p>
          )}
        </div>
        {chosen && (
          <Collapsible>
            <CollapsibleTrigger className="group flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
              <ChevronRight className="size-4 transition-transform group-data-[panel-open]:rotate-90" />{" "}
              Start options
            </CollapsibleTrigger>
            <CollapsibleContent className="mt-2 flex flex-col gap-3 rounded-lg border p-3">
              <Label className="flex items-center gap-2 font-normal">
                <Switch
                  checked={paused}
                  onCheckedChange={(v) => setPaused(v)}
                />{" "}
                Start paused
              </Label>
            </CollapsibleContent>
          </Collapsible>
        )}
        {template && (
          <Label className="flex items-center gap-2 font-normal">
            <Switch checked={paused} onCheckedChange={(v) => setPaused(v)} />{" "}
            Start paused
          </Label>
        )}
        <Tip
          content={
            disabled
              ? "The server runs as many worlds as it may; close one first"
              : undefined
          }
        >
          <Button
            type="submit"
            disabled={
              disabled ||
              (!chosen && !template) ||
              !nameOk ||
              paramErrors.some((x) => x !== null)
            }
          >
            Start world
          </Button>
        </Tip>
      </form>
    </PanelSection>
  );
}
