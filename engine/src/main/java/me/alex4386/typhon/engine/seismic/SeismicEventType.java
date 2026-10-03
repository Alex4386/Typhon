package me.alex4386.typhon.engine.seismic;

/** Classes of volcanic seismicity, following the usual observatory taxonomy. */
public enum SeismicEventType {
    /** Volcano-tectonic: brittle rock failure around a pressurising chamber or propagating dike. */
    VT,
    /** Long-period: resonance of fluid-filled cracks as magma or gas moves through the conduit. */
    LP,
    /** Volcanic tremor: sustained ground vibration during eruptions or vigorous degassing. */
    TREMOR,
    /** Explosion quake from a vent explosion. */
    EXPLOSION
}
