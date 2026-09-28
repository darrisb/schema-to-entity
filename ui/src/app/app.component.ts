import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { SidebarComponent } from './sidebar/sidebar.component';
import { SchemaService } from './services/schema.service';

interface SchemaColumn {
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

interface SchemaTable {
  name?: string;
  table_name?: string;
  tableName?: string;
  columns?: SchemaColumn[];
  fields?: SchemaColumn[];
  foreignKeys?: unknown[];
  foreign_keys?: unknown[];
}

interface SchemaPayload {
  source?: string;
  dialect?: string;
  schema?: string;
  tables?: SchemaTable[];
}

interface SpringProperty {
  name?: string;
  columnName?: string;
  type?: string;
  nullable?: boolean;
}

interface SpringRelationship {
  name?: string;
  type?: string;
  targetEntity?: string;
}

interface SpringEntity {
  entityName?: string;
  tableName?: string;
  properties?: SpringProperty[];
  relationships?: SpringRelationship[];
}

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [SidebarComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="layout">
      <app-sidebar />
      <main class="content">
        <section class="panel">
          <div class="panel-heading">
            <div>
              <p class="eyebrow">Schema</p>
              <h1>{{ schemaTitle() }}</h1>
            </div>
            @if (tables().length) {
              <div class="stats" aria-label="Schema summary">
                <span>{{ tables().length }} table(s)</span>
                <span>{{ columnCount() }} column(s)</span>
              </div>
            }
          </div>

          @if (tables().length) {
            <div class="table-grid">
              @for (table of tables(); track tableName(table)) {
                <article class="table-card">
                  <header>
                    <h2>{{ tableName(table) }}</h2>
                    <span>{{ tableColumns(table).length }} column(s)</span>
                  </header>
                  <div class="columns">
                    @for (column of tableColumns(table); track columnName(column)) {
                      <div class="column-row">
                        <div>
                          <strong>{{ columnName(column) }}</strong>
                          <span>{{ columnType(column) }}</span>
                        </div>
                        <div class="badges">
                          @if (isPrimaryKey(column)) {
                            <span>PK</span>
                          }
                          @if (!isNullable(column)) {
                            <span>Required</span>
                          }
                        </div>
                      </div>
                    }
                  </div>
                  @if (foreignKeyCount(table) > 0) {
                    <p class="relationship-note">{{ foreignKeyCount(table) }} foreign key(s)</p>
                  }
                </article>
              }
            </div>
          } @else {
            <div class="empty-state">
              <h2>No schema loaded</h2>
              <p>Load the sample schema or connect to PostgreSQL to preview tables here.</p>
            </div>
          }
        </section>

        @if (entities().length) {
          <section class="panel">
            <div class="panel-heading">
              <div>
                <p class="eyebrow">Spring metadata</p>
                <h1>Generated entities</h1>
              </div>
              <div class="stats">
                <span>{{ entities().length }} entity(s)</span>
              </div>
            </div>
            <div class="entity-list">
              @for (entity of entities(); track entity.entityName || entity.tableName) {
                <article class="entity-row">
                  <div>
                    <h2>{{ entity.entityName }}</h2>
                    <p>{{ entity.tableName }}</p>
                  </div>
                  <div class="stats compact">
                    <span>{{ entity.properties?.length || 0 }} property(s)</span>
                    <span>{{ entity.relationships?.length || 0 }} relation(s)</span>
                  </div>
                </article>
              }
            </div>
          </section>
        }

        @if (schemaJson(); as json) {
          <section class="panel raw-panel">
            <div class="panel-heading">
              <div>
                <p class="eyebrow">Raw schema JSON</p>
                <h1>Current payload</h1>
              </div>
            </div>
            <pre>{{ json }}</pre>
          </section>
        }
      </main>
    </div>
  `,
  styles: [
    `
      .layout {
        display: flex;
        min-height: 100vh;
        background: #f4f7fb;
      }
      .content {
        flex: 1;
        min-width: 0;
        overflow: auto;
        padding: 24px;
      }
      .panel {
        margin: 0 auto 20px;
        max-width: 1120px;
        background: #fff;
        border: 1px solid #dbe3ef;
        border-radius: 8px;
        padding: 20px;
        box-shadow: 0 12px 28px rgb(15 23 42 / 0.06);
      }
      .panel-heading {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: 16px;
        margin-bottom: 18px;
      }
      .eyebrow {
        margin: 0 0 4px;
        color: #64748b;
        font-size: 12px;
        font-weight: 700;
        letter-spacing: 0;
        text-transform: uppercase;
      }
      h1,
      h2,
      p {
        margin: 0;
      }
      h1 {
        color: #0f172a;
        font-size: 24px;
      }
      h2 {
        color: #111827;
        font-size: 15px;
      }
      .stats,
      .badges {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
      }
      .stats span,
      .badges span {
        border: 1px solid #cbd5e1;
        border-radius: 999px;
        color: #334155;
        font-size: 12px;
        font-weight: 600;
        padding: 5px 9px;
        white-space: nowrap;
      }
      .table-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
        gap: 14px;
      }
      .table-card {
        border: 1px solid #e2e8f0;
        border-radius: 8px;
        overflow: hidden;
        background: #fbfdff;
      }
      .table-card header,
      .entity-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12px;
      }
      .table-card header {
        border-bottom: 1px solid #e2e8f0;
        padding: 12px 14px;
      }
      .table-card header span,
      .entity-row p,
      .relationship-note,
      .empty-state p {
        color: #64748b;
        font-size: 13px;
      }
      .columns {
        display: grid;
      }
      .column-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12px;
        min-height: 48px;
        padding: 10px 14px;
      }
      .column-row + .column-row {
        border-top: 1px solid #edf2f7;
      }
      .column-row strong,
      .column-row span {
        display: block;
      }
      .column-row strong {
        color: #0f172a;
        font-size: 13px;
      }
      .column-row div > span {
        color: #64748b;
        font-size: 12px;
        margin-top: 3px;
      }
      .relationship-note {
        border-top: 1px solid #edf2f7;
        padding: 10px 14px;
      }
      .empty-state {
        display: grid;
        gap: 8px;
        min-height: 220px;
        place-content: center;
        text-align: center;
      }
      .entity-list {
        display: grid;
        gap: 10px;
      }
      .entity-row {
        border: 1px solid #e2e8f0;
        border-radius: 8px;
        padding: 12px 14px;
      }
      .compact {
        justify-content: flex-end;
      }
      .raw-panel pre {
        max-height: 420px;
        overflow: auto;
        background: #0f172a;
        border-radius: 8px;
        color: #e5edf8;
        font-size: 12px;
        line-height: 1.5;
        margin: 0;
        padding: 16px;
      }
      @media (max-width: 760px) {
        .layout {
          flex-direction: column;
        }
        .content {
          padding: 16px;
        }
        .panel-heading,
        .column-row,
        .entity-row {
          align-items: stretch;
          flex-direction: column;
        }
      }
    `
  ]
})
export class AppComponent {
  private readonly schemaService = inject(SchemaService);

  readonly schema = computed(() => this.normalizeSchema(this.schemaService.enrichedSchema()));
  readonly tables = computed(() => this.schema()?.tables || []);
  readonly entities = computed(() => this.schemaService.springMetadata()?.entities as SpringEntity[] || []);
  readonly columnCount = computed(() =>
    this.tables().reduce((count, table) => count + this.tableColumns(table).length, 0)
  );
  readonly schemaTitle = computed(() => {
    const schema = this.schema();
    if (!schema) return 'Waiting for input';
    const dialect = schema.dialect || schema.source || 'database';
    return schema.schema ? `${schema.schema} (${dialect})` : dialect;
  });
  readonly schemaJson = computed(() => {
    const schema = this.schemaService.enrichedSchema();
    return schema ? JSON.stringify(schema, null, 2) : null;
  });

  tableName(table: SchemaTable): string {
    return table.name || table.table_name || table.tableName || 'unknown_table';
  }

  tableColumns(table: SchemaTable): SchemaColumn[] {
    return table.columns || table.fields || [];
  }

  columnName(column: SchemaColumn): string {
    return column.name || column.column_name || column.columnName || 'unknown_column';
  }

  columnType(column: SchemaColumn): string {
    return column.type || column.data_type || column.dataType || 'unknown';
  }

  isPrimaryKey(column: SchemaColumn): boolean {
    return Boolean(column.primaryKey || column.primary_key);
  }

  isNullable(column: SchemaColumn): boolean {
    return column.nullable !== false;
  }

  foreignKeyCount(table: SchemaTable): number {
    return (table.foreignKeys || table.foreign_keys || []).length;
  }

  private normalizeSchema(value: unknown): SchemaPayload | null {
    if (!value || typeof value !== 'object') return null;
    const schema = value as SchemaPayload;
    return Array.isArray(schema.tables) ? schema : null;
  }
}
