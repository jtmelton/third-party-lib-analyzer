class Edge {
    constructor(source, target, colour) {
        this.source = source;
        this.target = target;
        this.colour = colour;
    }

    draw() {
        let opacity = 60;

        if(this.source.isSelected() || this.target.isSelected()) {
            opacity = 255;
        }

        stroke(this.colour.levels[0], this.colour.levels[1], this.colour.levels[2], opacity);
        strokeWeight(1.5);
        line(this.source.x, this.source.y, this.target.x, this.target.y)
    }
}