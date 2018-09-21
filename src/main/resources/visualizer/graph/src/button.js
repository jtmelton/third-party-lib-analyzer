class Button {
	constructor(x, y, width, height, text, p5) {
		this.x = x;
		this.y = y;
		this.w = width;
		this.h = height;
		this.padding = 5;
		this.text = new Text(x + width / 2, (y + height / 2) + this.padding, 15, p5.CENTER);
		this.text.setText(text);
		this.active = true;
	}

	setY(y) {
		this.y = y;
		this.text.y = (y + this.h / 2) + this.padding;
	}

	setX(x) {
		this.x = x;
		this.text.x = x + this.w / 2;
	}

	draw() {
		push();
		if(this.active) {
			stroke(255);
			fill('#e2a20d');
		} else {
			stroke(255, 255, 255, 100);
			fill('#e2a20d64');
		}
		rect(this.x, this.y, this.w, this.h);
		pop();
		this.text.draw();
	}
}