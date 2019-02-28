let nodes = [];
let edgesArr = [];

let scaleFactor = 1;
let translateX = 0;
let translateY = 0;
let prevMouseX = 0;
let prevMouseY = 0;

let halfWidth;
let halfHeight;

let legend;
let stats = []

let nodeText;

let clickedNode;
let movingNode = false;
let movingScreen = false;

let mouseOffsetX = 0;
let mouseOffsetY = 0;

let clusterPoints = [];

let translatingTo = null;

function setup() {
    createCanvas(windowWidth, windowHeight);

    halfWidth = windowWidth / 2;
    halfHeight = windowHeight / 2;

    legend = new Legend(0, 0, this);

    nodeText = new Text(20, 30, 20, LEFT);
    nodeText.setText("Select a Node");

    // Get cluster info, create cluster colors, and create nodes
    let colors = [];
    let clusterSizes = [];
    let clusterNames = [];
    for(node of data.nodes) {
        if(isNull(colors[node.cluster])) {
            let r = Math.floor(random(255));
            let g = Math.floor(random(255));
            let b = Math.floor(random(255));
            colors[node.cluster] = color(r, g, b);
            clusterSizes[node.cluster] = 0
        }

        if(isNull(clusterNames[node.cluster])) {
            clusterNames[node.cluster] = node.jar;
        }

        clusterSizes[node.cluster] += 1;
        let c = colors[node.cluster];
        nodes[node.id] = new Node(node.id, node.name, 0, 0, c, node.cluster)
    }

    // Set up cluster locations
    let placedCluster = [];
    let r = log(colors.length) * 700;
    for(let i = 1;i <= colors.length;i++) {
        let cluster = selectClusterPos(clusterSizes, placedCluster, i, r);

        clusterPoints[i] = cluster;
        placedCluster.push(cluster);
    }

    // Move nodes to respective cluster location
    for(let i = 1;i < nodes.length;i++) {
        let node = nodes[i];
        let cluster = clusterPoints[node.cluster];

        let cx = cluster.x;
        let cy = cluster.y;
        let cr = cluster.r;
        let p = random(0, TWO_PI);

        let x = cx + (sin(p) * random(1, cr / 2));
        let y = cy + (cos(p) * random(1, cr / 2));

        node.x = x;
        node.y = y;
    }

    // Create edges
    for(edge of data.edges) {
        let source = nodes[edge.source];
        let target = nodes[edge.target];

        let c1 = colors[source.cluster];
        let c2 = colors[target.cluster];

        let gradient = getColorGradient(c1.levels, c2.levels);

        edgesArr.push(new Edge(source, target, gradient))
    }

    // Create legend
    for(let i = 1;i < colors.length;i++) {
        let t = new Text(0, 0, 15, LEFT);
        t.setText(clusterNames[i])
        legend.addEntry(t, colors[i], i);
    }

    // Create stats
    let labels = [
        { label: "Clusters", count: colors.length - 1 }, 
        { label: "Classes", count: data.nodes.length }, 
        { label: "Edges", count: data.edges.length - 1 }
    ];
    for(let i = 0;i < 3;i++) {
        let t = new Text(0, 0, 15, RIGHT);
        t.setText(labels[i].count + " : " + labels[i].label);
        t.y = (i * 20) + 35;
        t.x = windowWidth - 15;
        stats.push(t);
    }
}

function draw() {
    background(0);

    updateTranslateTo();
    nodeSelection();
    mouseMovement();

    // push and pop so ONLY nodes and edges are 
    // scaled and translated
    push();
    translate(translateX, translateY);
    scale(scaleFactor);
    for(edge of edgesArr) {
        edge.draw();
    }
    for(let i = 1;i < nodes.length;i++) {
        nodes[i].draw();
    }
    pop();

    nodeText.draw();
    legend.draw();

    for(let t of stats) {
        t.draw();
    }
}

function nodeSelection() {
    if(movingScreen || !isNull(translatingTo)) {
        return;
    }

    if(!mouseIsPressed) {
        movingNode = false;
        return;
    }

    let mX = (mouseX - translateX) / scaleFactor;
    let mY = (mouseY - translateY) / scaleFactor;

    if(movingNode) {
        clickedNode.x = mX;
        clickedNode.y = mY;
        return;
    }

    for(let i = 1;i < nodes.length;i++) {
        let node = nodes[i];
        let r = node.diameter / 2;

        let mouse = { x: mX, y: mY, r: 1 }
        let overlaps = circleOverlap(mouse, node);

        if(!overlaps) {
            continue;
        }

        highlightNode(node);

        movingNode = true;
        break;
    }
}

function highlightNode(node) {
    if(!isNull(clickedNode)) {
        clickedNode.setSelected(false);
        legend.getEntry(clickedNode.cluster).highlight(false)
    }

    clickedNode = node;
    clickedNode.setSelected(true)
    nodeText.setText(clickedNode.name);
    legend.getEntry(clickedNode.cluster).highlight(true)
}

function selectClusterPos(clusterSizes, placedCluster, i, r) {
    let cluster = null;
    let maxTries = 20;
    let attempts = 0;
    
    while(taken(placedCluster, cluster) && attempts < maxTries) {
        let p = random(0, TWO_PI);
        let x = halfWidth + (sin(p) * random(1, r / 2));
        let y = halfHeight + (cos(p) * random(1, r / 2));

        cluster = new ClusterPoint(x, y, log(clusterSizes[i]) * 50);
        attempts += 1;
    }

    return cluster;
}

function taken(arr, cluster) {
    if(isNull(cluster)) {
        return true;
    }

    for(let other of arr) {
        if(circleOverlap(other, cluster)) {
            return true;
        }
    }

    return false;
}

function mouseMovement() {
    if(movingNode) {
        return;
    }

    if(mouseIsPressed && isNull(translatingTo)) {
        translateX -= prevMouseX - mouseX;
        translateY -= prevMouseY - mouseY;
        movingScreen = true;
    } else {
        movingScreen = false;
    }

    prevMouseX = mouseX;
    prevMouseY = mouseY;
}

function mouseWheel(event) {
    let oldScale = scaleFactor
    scaleFactor -= event.delta * 0.0005;

    if(scaleFactor > 0) {
        translateX += event.delta * ((mouseX - translateX) / oldScale) * 0.0005;
        translateY += event.delta * ((mouseY - translateY) / oldScale) * 0.0005;
    } else {
        scaleFactor = 0;
    }
}

function mousePressed() {
    legend.checkPageClick(mouseX, mouseY);
    let cluster = legend.checkEntryClick(mouseX, mouseY);

    if(!isNull(cluster)) {
        translatingTo = clusterPoints[cluster];
        for(let i = 1;i < nodes.length;i++) {
            if(nodes[i].cluster != cluster) {
                continue;
            }

            highlightNode(nodes[i]);
            break;
        }
    }
}

function updateTranslateTo() {
    if(isNull(translatingTo)) {
        return;
    }

    let centerX = ((halfWidth - translateX) / scaleFactor);
    let centerY = ((halfHeight - translateY) / scaleFactor);

    let angle = -atan2(centerY - translatingTo.y, centerX - translatingTo.x);

    let circle1 = { x: translatingTo.x, y: translatingTo.y };
    let circle2 = { x: centerX, y: centerY };
    let dist = distance(circle1, circle2);

    let speedScaler = 0.09;
    let speed = (dist * speedScaler) * scaleFactor;

    let c = cos(angle) * speed;
    let s = sin(angle) * speed;

    translateX += c;
    translateY -= s;

    if(dist < 5) {
        translatingTo = null;
    }
}

class ClusterPoint {
    constructor(x, y, r) {
        this.x = x;
        this.y = y;
        this.r = r;
        this.diameter = r * 2;
    }
}