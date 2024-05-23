package me.alex4386.plugin.typhon.volcano.landslide;

import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;

public class VolcanoLandslide {
    public VolcanoVent vent;
    public double landslideAngle = 0;
    public int initSummitY = Integer.MIN_VALUE;

    public VolcanoLandslide(VolcanoVent vent) {
        this.vent = vent;
    }

    public void configure() {
        this.initSummitY = (int) (this.vent.getSummitBlock().getY() + (this.vent.getRadius() / Math.sqrt(3)));
    }

    public int getRadius() {
        if (this.initSummitY == Integer.MIN_VALUE) {
            this.configure();
        }

        return (int) ((this.initSummitY - this.vent.location.getY()) / 4);
    }

    public int getRimSummitY() {
        return this.initSummitY + (int) Math.ceil(Math.sqrt(3) * this.getRadius());
    }

    public int getRimY(double x) {
        double topOffset = Math.max(-this.getRadius(), x) + this.getRadius();
        double deductionCount = -topOffset / Math.sqrt(3);

        return (int) Math.round(this.getRimSummitY() + deductionCount);
    }

    public int getFloorY(double x) {
        if (x < 0 && x > -this.getRadius()) {
            return (int) (this.getRimSummitY() - (this.getRadius() + x));
        }

        double topOffset = Math.max(-this.getRadius(), x) + this.getRadius();
        double deductionCount = -topOffset / (1 / Math.tan(Math.PI / 12));

        return (int) Math.round(this.getRimSummitY() + deductionCount - this.getRadius());
    }

    public int spreadOutY() {
        return (int) (3*this.getRadius() - Math.sqrt(3)*this.getRadius()/(-6 + 4 * Math.sqrt(3)));
    }

}
