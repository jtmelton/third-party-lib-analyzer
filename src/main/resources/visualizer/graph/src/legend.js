class Legend {
	constructor(x, y, p5) {
		this.x = x;
		this.y = y;
		this.entries = [];
		this.entries[0] = [];
		this.addEntryIndex = 0;
		this.currentPage = 0;
		this.previousButton = new Button(x + 40, 0, 65, 20, "Previous", p5);
		this.nextButton = new Button(x + 140, 0, 65, 20, "Next", p5);
	}

	addEntry(text, color, clusterId) {
		let page;
		if(this.entries[this.addEntryIndex].length < 10) {
			page = this.entries[this.addEntryIndex];
		} else {
			this.addEntryIndex += 1;
			this.entries[this.addEntryIndex] = [];
			page = this.entries[this.addEntryIndex];
		}

		text.x = this.x + 40;
		text.y = this.y + (((page.length + 1) * 25) + 35);
		page.push(new LegendEntry(text, color, clusterId));

		this.previousButton.setY(this.y + (this.entries[0].length * 30));
		this.nextButton.setY(this.y + (this.entries[0].length * 30));
	}

	draw() {
		let page = this.entries[this.currentPage];
		for(let entry of page) {
			entry.draw();
		}

		if(this.entries.length == 1) {
			return;
		}

		if(this.currentPage != 0) {
			this.previousButton.active = true;
		} else {
			this.previousButton.active = false;
		}

		if(this.entries.length > 0 && this.currentPage != this.entries.length - 1) {
			this.nextButton.active = true;
		} else {
			this.nextButton.active = false;
		}

		this.previousButton.draw();
		this.nextButton.draw();
	}

	checkPageClick(mX, mY) {
		if(this.currentPage > 0 
			&& this.checkClick(this.previousButton, mX, mY)) {

			this.currentPage -= 1;
			return true;
		} else if(this.currentPage < this.entries.length 
			&& this.checkClick(this.nextButton, mX, mY)) {
			
			this.currentPage += 1;
			return true;
		}

		return false;
	}

	checkClick(button, mX, mY) {
		let mouse = { x: mX, y: mY, w: 1, h: 1 };
		return button.active && boxOverlap(button, mouse);
	}

	checkEntryClick(mX, mY) {
		let mouse = { x: mX, y: mY, w: 1, h: 1 };
		for(let entry of this.entries[this.currentPage]) {
			if(boxOverlap(mouse, entry.box)) {
				return entry.clusterId;
			}
		}

		return null;
	}

	getEntry(index) {
		let offSetIndex = index - 1;
		let page = Math.floor(offSetIndex / 10);
		let pageIndex = offSetIndex % 10;
		this.currentPage = page;
		return this.entries[page][pageIndex];
	}
}

class LegendEntry {
	constructor(text, color, clusterId) {
		this.text = text;
		this.color = color;
		this.highlighted = false;
		this.period = 0;
		this.minRadius = 11;
		this.startRadius = 50;
		this.currentRadius = 50;
		this.clusterId = clusterId;

		this.box = { 
			x: this.text.x - this.text.size - 5,
			y: this.text.y - this.text.size,
			w: this.text.size,
			h: this.text.size
		};
	}

	draw() {
		this.text.draw()
		push();
		fill(this.color)
		stroke(255)
		rect(this.box.x, this.box.y, this.box.w, this.box.h);
		pop();

		if(this.highlighted) {
			let x = this.box.x + this.box.w / 2;
			let y = this.box.y + this.box.h / 2;
			this.drawPoints(x, y, this.period, this.currentRadius);
		} else {
			this.currentRadius = this.startRadius;
		}

		this.period += 0.09;
	}

	highlight(highlighted) {
		this.highlighted = highlighted;
	}

	drawPoints(x, y, p, r) {
		if(r > this.minRadius) {
			this.currentRadius -= 1;
		}

		drawSelectionPoints(x, y, p, r)
	}
}
