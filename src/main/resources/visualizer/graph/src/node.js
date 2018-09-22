class Node {
    constructor(id, name, x, y, colour, cluster) {
        this.id = id;
        this.name = name;
        this.x = x;
        this.y = y;
        this.colour = colour;
        this.cluster = cluster;
        this.diameter = 10;
        this.r = this.diameter / 2;
        this.selected = false;
        this.period = 0;
        this.minRadius = 11;
        this.startRadius = 50;
        this.currentRadius = 50;
    }

    draw() {
        fill(this.colour)
        stroke(255);
        strokeWeight(1);

        if(this.selected) {
            this.drawPoints(this.x, this.y, this.period, this.currentRadius);
        } else {
            this.currentRadius = this.startRadius;
        }

        ellipse(this.x, this.y, this.diameter, this.diameter);

        this.period += 0.09;
    }

    setSelected(value) {
        this.selected = value;
    }

    isSelected() {
        return this.selected;
    }

    drawPoints(x, y, p, r) {
        if(r > this.minRadius) {
            this.currentRadius -= 1;
        }

        drawSelectionPoints(x, y, p, r)
    }
}
