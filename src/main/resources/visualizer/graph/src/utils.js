function drawSelectionPoints(x, y, p, r) {
    push();
    fill(255);
    stroke(255);
    strokeWeight(7);
    let sX = x + (sin(p) * r);
    let sY = y + (cos(p) * r);
    point(sX, sY);

    sX = x + (sin(p + PI) * r);
    sY = y + (cos(p + PI) * r);
    point(sX, sY);

    sX = x + (sin(p + HALF_PI) * r);
    sY = y + (cos(p + HALF_PI) * r);
    point(sX, sY);

    sX = x + (sin(p + PI + HALF_PI) * r);
    sY = y + (cos(p + PI + HALF_PI) * r);
    point(sX, sY);
    pop();
}

function isNull(variable) {
    if (typeof(variable) !== 'undefined' && variable != null)
    {
        return false;
    }

    return true;
}

function circleOverlap(c1, c2) {
    return distance(c1, c2) < c1.r + c2.r;
}

function distance(c1, c2) {
	let dx = c1.x - c2.x;
    let dy = c1.y - c2.y;

    return Math.sqrt(dx * dx + dy * dy);
}

function boxOverlap(box1, box2) {
    return box1.x + box1.w > box2.x
        && box1.y + box1.h > box2.y
        && box1.x < box2.x + box2.w
        && box1.y < box2.y + box2.h;
}

function getColorGradient(color1, color2) {
    let percent = 0.5;

    let r = Math.floor(color1[0] + percent * (color2[0] - color1[0]));
    let g = Math.floor(color1[1] + percent * (color2[1] - color1[1]));
    let b = Math.floor(color1[2] + percent * (color2[2] - color1[2]));

    return color(r, g, b);
}