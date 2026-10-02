import {
  ChangeDetectionStrategy,
  Component,
  input,
  output,
  signal,
} from '@angular/core';

interface BuilderColumn {
  name?: string;
  column_name?: string;
  columnName?: string;
  type?: string;
  data_type?: string;
  dataType?: string;
  nullable?: boolean;
  primaryKey?: boolean;
  primary_key?: boolean;
}

interface BuilderTable {
  name?: string;
  table_name?: string;
  tableName?: string;
  columns?: BuilderColumn[];
  fields?: BuilderColumn[];
}

interface BuilderField {
  key: string;
  tableName: string;
  column: BuilderColumn;
}

@Component({
  selector: 'app-page-builder',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="builder">
      <header class="topbar">
        <button class="back-button" type="button" (click)="back.emit()">
          <span aria-hidden="true">←</span>
          <span>Back to schema</span>
        </button>
        <div class="page-title">
          <p class="eyebrow">Schema / Low-code workspace</p>
          <h1>Page Builder</h1>
        </div>
        <div class="topbar-meta">
          <span>{{ tables().length }} tables</span>
          <span>{{ fields().length }} fields</span>
        </div>
      </header>

      <div class="workspace">
        <aside class="sources-panel" aria-label="Selected tables">
          <div class="panel-heading">
            <div>
              <p class="eyebrow">Data sources</p>
              <h2>Selected tables</h2>
            </div>
            <span class="count-badge">{{ tables().length }}</span>
          </div>

          <div class="table-list">
            @for (table of tables(); track tableName(table); let index = $index) {
              <section
                class="source-table"
                [class.expanded]="isExpanded(table, index)"
              >
                <div class="source-table-heading">
                  <button
                    class="collapse-button"
                    type="button"
                    [attr.aria-expanded]="isExpanded(table, index)"
                    [attr.aria-label]="
                      (isExpanded(table, index) ? 'Collapse ' : 'Expand ') +
                      tableName(table)
                    "
                    (click)="toggleExpanded(table)"
                  >
                    {{ isExpanded(table, index) ? '−' : '+' }}
                  </button>
                  <button
                    class="table-name-button"
                    type="button"
                    [attr.aria-expanded]="isExpanded(table, index)"
                    (click)="toggleExpanded(table)"
                  >
                    <strong>{{ tableName(table) }}</strong>
                    <span>{{ schemaLabel() }}</span>
                  </button>
                  <span class="column-count"
                    >{{ tableColumns(table).length }}</span
                  >
                </div>

                @if (isExpanded(table, index)) {
                  <div class="column-list">
                    @for (
                      column of tableColumns(table);
                      track columnName(column)
                    ) {
                      <button
                        class="column-option"
                        type="button"
                        [class.is-selected]="isFieldSelected(table, column)"
                        [attr.aria-pressed]="isFieldSelected(table, column)"
                        (click)="addField(table, column)"
                      >
                        <span class="column-kind">{{
                          columnKind(column)
                        }}</span>
                        <span class="column-label">
                          <strong>{{ columnName(column) }}</strong>
                          <small>{{ columnType(column) }}</small>
                        </span>
                        @if (isFieldSelected(table, column)) {
                          <span class="selected-mark" aria-label="Added"
                            >✓</span
                          >
                        }
                      </button>
                    } @empty {
                      <p class="empty-columns">No columns in this table.</p>
                    }
                  </div>
                }
              </section>
            } @empty {
              <p class="empty-tables">No tables were selected.</p>
            }
          </div>
          <p class="sidebar-hint">Choose a column to add it to your page.</p>
        </aside>

        <section class="preview-panel" aria-label="Page preview">
          <div class="preview-heading">
            <div>
              <p class="eyebrow">Live preview / Form</p>
              <h2>Arrange your experience</h2>
            </div>
            <span class="count-badge">{{ fields().length }} fields</span>
          </div>

          <div class="preview-canvas">
            @if (fields().length) {
              <div class="form-preview">
                <div class="form-title">
                  <p class="eyebrow">Form</p>
                  <h3>New record</h3>
                </div>
                @for (field of fields(); track field.key) {
                  <div class="form-field">
                    <label [for]="'field-' + field.key">
                      {{ columnName(field.column) }}
                      @if (field.column.nullable === false) {
                        <span class="required-mark" aria-label="Required"
                          >*</span
                        >
                      }
                    </label>
                    @if (isBoolean(field.column)) {
                      <label class="checkbox-field">
                        <input id="field-{{ field.key }}" type="checkbox" />
                        {{ columnName(field.column) }}
                      </label>
                    } @else {
                      <input
                        [id]="'field-' + field.key"
                        [type]="inputType(field.column)"
                        [placeholder]="columnType(field.column)"
                      />
                    }
                    <div class="field-meta">
                      <span>{{ field.tableName }}</span>
                      <button
                        type="button"
                        [attr.aria-label]="
                          'Remove ' + columnName(field.column) + ' from form'
                        "
                        (click)="removeField(field.key)"
                      >
                        Remove
                      </button>
                    </div>
                  </div>
                }
              </div>
            } @else {
              <div class="empty-preview">
                <span class="add-icon" aria-hidden="true">+</span>
                <h3>Click a column to add it here.</h3>
                <p>
                  Choose a column from the selected tables to start building
                  your form.
                </p>
              </div>
            }
          </div>
        </section>
      </div>
    </main>
  `,
  styles: [
    `
      :host {
        display: block;
        min-height: 100vh;
        color: #26322f;
      }
      .builder {
        box-sizing: border-box;
        min-height: 100vh;
        padding: 0 3.4vw 26px;
        background: #f5f3ef;
      }
      .topbar {
        display: flex;
        align-items: center;
        gap: 16px;
        min-height: 52px;
        margin-bottom: 1.25vh;
      }
      .back-button {
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 7px;
        min-height: 34px;
        padding: 0 10px;
        border: 1px solid #d9ded9;
        border-radius: 18px;
        background: transparent;
        color: #53635d;
        font-size: 12px;
        cursor: pointer;
      }
      .back-button span:first-child {
        font-size: 17px;
      }
      .page-title {
        flex: 1;
      }
      .eyebrow {
        margin: 0 0 4px;
        color: #74817b;
        font-size: 9px;
        font-weight: 700;
        letter-spacing: 0.1em;
        text-transform: uppercase;
      }
      h1,
      h2,
      h3,
      p {
        margin-top: 0;
      }
      h1,
      h2,
      h3 {
        color: #26322f;
        font-family: Georgia, 'Times New Roman', serif;
        font-weight: 500;
      }
      h1 {
        margin-bottom: 0;
        font-size: 22px;
      }
      .topbar-meta {
        display: flex;
        gap: 8px;
      }
      .topbar-meta span,
      .count-badge {
        padding: 6px 9px;
        border-radius: 4px;
        background: #dff0e9;
        color: #397968;
        font-size: 10px;
        font-weight: 700;
        white-space: nowrap;
      }
      .workspace {
        display: grid;
        grid-template-columns: minmax(245px, 21.5%) minmax(0, 1fr);
        gap: 14px;
        min-height: calc(100vh - 78px);
      }
      .sources-panel,
      .preview-panel {
        min-width: 0;
        border: 1px solid #dce1dc;
        border-radius: 9px;
        background: #fffefc;
        box-shadow: 0 10px 24px rgb(31 45 39 / 0.04);
      }
      .sources-panel {
        display: flex;
        flex-direction: column;
        padding: 16px 12px 12px;
      }
      .panel-heading,
      .preview-heading {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12px;
        border-bottom: 1px solid #e7e8e3;
      }
      .panel-heading {
        padding: 0 0 12px;
      }
      .panel-heading h2,
      .preview-heading h2 {
        margin-bottom: 0;
        font-size: 18px;
      }
      .table-list {
        display: grid;
        align-content: start;
        gap: 8px;
        overflow: auto;
        padding: 10px 0;
      }
      .source-table {
        overflow: hidden;
        border: 1px solid #e0e5df;
        border-radius: 6px;
        background: #fffefc;
      }
      .source-table.expanded {
        border-color: #bad8ca;
        background: #f8fbf8;
      }
      .source-table-heading {
        display: flex;
        align-items: center;
        gap: 8px;
        min-height: 46px;
        padding: 0 8px;
      }
      .collapse-button {
        width: 14px;
        padding: 0;
        border: 0;
        background: transparent;
        color: #a55236;
        font-size: 17px;
        cursor: pointer;
      }
      .table-name-button {
        display: grid;
        flex: 1;
        gap: 3px;
        padding: 6px 0;
        border: 0;
        background: transparent;
        text-align: left;
        cursor: pointer;
      }
      .table-name-button strong {
        color: #34443d;
        font-size: 11px;
      }
      .table-name-button span,
      .column-label small {
        color: #86918b;
        font-size: 9px;
      }
      .column-count {
        color: #86918b;
        font-size: 9px;
      }
      .column-list {
        padding: 0 7px 5px;
      }
      .column-option {
        display: flex;
        align-items: center;
        gap: 9px;
        width: 100%;
        min-height: 40px;
        padding: 5px 2px;
        border: 0;
        border-top: 1px solid #e8ece7;
        background: transparent;
        text-align: left;
        cursor: pointer;
      }
      .column-option:hover,
      .column-option.is-selected {
        background: #eff7f2;
      }
      .column-kind {
        width: 13px;
        color: #729284;
        font-size: 8px;
        text-align: center;
      }
      .column-label {
        display: grid;
        flex: 1;
        gap: 3px;
        min-width: 0;
      }
      .column-label strong {
        overflow: hidden;
        color: #34443d;
        font-size: 10px;
        text-overflow: ellipsis;
      }
      .selected-mark {
        color: #287a66;
        font-size: 12px;
      }
      .empty-columns,
      .empty-tables,
      .sidebar-hint {
        color: #849089;
        font-size: 11px;
      }
      .empty-columns {
        margin: 0;
        padding: 10px 4px;
      }
      .empty-tables {
        margin: 0;
        padding: 16px 5px;
      }
      .sidebar-hint {
        margin: auto 0 0;
        padding-top: 8px;
        border-top: 1px solid #e7e8e3;
      }
      .preview-panel {
        display: flex;
        flex-direction: column;
        padding: 16px;
      }
      .preview-heading {
        padding: 0 0 12px;
      }
      .preview-canvas {
        display: grid;
        flex: 1;
        min-height: 420px;
        padding-top: 12px;
      }
      .empty-preview {
        align-self: center;
        justify-self: center;
        max-width: 290px;
        text-align: center;
      }
      .add-icon {
        display: grid;
        width: 44px;
        height: 44px;
        place-items: center;
        margin: 0 auto 10px;
        border: 1px dashed #a9c8b9;
        border-radius: 50%;
        color: #448b72;
        font-size: 21px;
      }
      .empty-preview h3 {
        margin-bottom: 6px;
        font-size: 18px;
      }
      .empty-preview p {
        margin-bottom: 0;
        color: #79857f;
        font-family: Georgia, 'Times New Roman', serif;
        font-size: 12px;
        line-height: 1.5;
      }
      .form-preview {
        width: min(100%, 680px);
        margin: 10px auto;
        padding: 24px;
        border: 1px solid #e0e4df;
        border-radius: 7px;
        background: #fff;
      }
      .form-title {
        margin-bottom: 20px;
        padding-bottom: 14px;
        border-bottom: 1px solid #edf0ec;
      }
      .form-title h3 {
        margin-bottom: 0;
        font-size: 20px;
      }
      .form-field {
        display: grid;
        gap: 7px;
        margin-bottom: 16px;
      }
      .form-field > label:first-child {
        color: #405047;
        font-size: 12px;
        font-weight: 600;
      }
      .required-mark {
        color: #b64a32;
      }
      .form-field > input {
        box-sizing: border-box;
        width: 100%;
        min-height: 38px;
        padding: 8px 10px;
        border: 1px solid #dce2dd;
        border-radius: 4px;
        background: #fff;
      }
      .checkbox-field {
        display: flex;
        align-items: center;
        gap: 7px;
        color: #65736b;
        font-size: 12px;
      }
      .field-meta {
        display: flex;
        align-items: center;
        justify-content: space-between;
        color: #89938e;
        font-size: 10px;
      }
      .field-meta button {
        padding: 0;
        border: 0;
        background: transparent;
        color: #a34d36;
        font-size: 10px;
        cursor: pointer;
      }
      @media (max-width: 700px) {
        .builder {
          padding: 0 12px 16px;
        }
        .topbar {
          flex-wrap: wrap;
          padding: 8px 0;
        }
        .topbar-meta {
          width: 100%;
          padding-left: 2px;
        }
        .workspace {
          grid-template-columns: 1fr;
          min-height: 0;
        }
        .sources-panel {
          max-height: 48vh;
        }
        .preview-canvas {
          min-height: 350px;
        }
        .form-preview {
          box-sizing: border-box;
          padding: 16px;
        }
      }
    `,
  ],
})
export class PageBuilderComponent {
  readonly tables = input.required<BuilderTable[]>();
  readonly schemaLabel = input('schema');
  readonly back = output<void>();
  readonly fields = signal<BuilderField[]>([]);
  private readonly expandedTables = signal<Set<string> | null>(null);

  tableName(table: BuilderTable): string {
    return table.name || table.table_name || table.tableName || 'unknown_table';
  }

  tableColumns(table: BuilderTable): BuilderColumn[] {
    return table.columns || table.fields || [];
  }

  columnName(column: BuilderColumn): string {
    return (
      column.name || column.column_name || column.columnName || 'unknown_column'
    );
  }

  columnType(column: BuilderColumn): string {
    return column.type || column.data_type || column.dataType || 'unknown';
  }

  columnKey(table: BuilderTable, column: BuilderColumn): string {
    return `${this.tableName(table)}.${this.columnName(column)}`;
  }

  isExpanded(table: BuilderTable, index: number): boolean {
    const expanded = this.expandedTables();
    return expanded ? expanded.has(this.tableName(table)) : index === 0;
  }

  toggleExpanded(table: BuilderTable): void {
    this.expandedTables.update((current) => {
      const expanded =
        current ??
        new Set(
          this.tables()
            .filter((_, tableIndex) => tableIndex === 0)
            .map((selectedTable) => this.tableName(selectedTable)),
        );
      const next = new Set(expanded);
      const tableName = this.tableName(table);
      if (next.has(tableName)) {
        next.delete(tableName);
      } else {
        next.add(tableName);
      }
      return next;
    });
  }

  isFieldSelected(table: BuilderTable, column: BuilderColumn): boolean {
    const key = this.columnKey(table, column);
    return this.fields().some((field) => field.key === key);
  }

  addField(table: BuilderTable, column: BuilderColumn): void {
    const key = this.columnKey(table, column);
    if (this.fields().some((field) => field.key === key)) return;
    this.fields.update((fields) => [
      ...fields,
      { key, tableName: this.tableName(table), column },
    ]);
  }

  removeField(key: string): void {
    this.fields.update((fields) => fields.filter((field) => field.key !== key));
  }

  columnKind(column: BuilderColumn): string {
    if (column.primaryKey || column.primary_key) return 'PK';
    return '·';
  }

  isBoolean(column: BuilderColumn): boolean {
    return this.columnType(column).toLowerCase().includes('bool');
  }

  inputType(column: BuilderColumn): string {
    const type = this.columnType(column).toLowerCase();
    if (type.includes('date') || type.includes('time')) return 'datetime-local';
    if (type.includes('int') || type.includes('numeric') || type.includes('decimal')) {
      return 'number';
    }
    return 'text';
  }
}
