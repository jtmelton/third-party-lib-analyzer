class Text {
	constructor(x, y, size, align) {
		this.x = x;
		this.y = y;
		this.size = size;
		this.text = "";
		this.align = align;
	}

	draw() {
		push();
		textAlign(this.align);
	    textSize(this.size);
	    fill(255);
	    text(this.text, this.x, this.y);
	    pop();
	}

	setText(text) {
		this.text = text;
	}

	getText() {
		return this.text;
	}
}