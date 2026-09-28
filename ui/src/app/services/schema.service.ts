import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

export interface SpringMetadataPayload {
  schemaVersion: string;
  dialect: string;
  generatedAt: string;
  entities: unknown[];
}

@Injectable({ providedIn: 'root' })
export class SchemaService {
  private readonly http = inject(HttpClient);

  readonly enrichedSchema = signal<unknown>(null);
  readonly springMetadata = signal<SpringMetadataPayload | null>(null);
  readonly exporting = signal(false);
  readonly extracting = signal(false);
  readonly lastExtractSummary = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  setSchema(schema: unknown): void {
    this.enrichedSchema.set(schema);
    this.springMetadata.set(null);
  }

  loadSample(): void {
    this.error.set(null);
    this.lastExtractSummary.set(null);
    this.http.get<unknown>('/api/schema/sample').subscribe({
      next: (schema) => {
        this.setSchema(schema);
        this.lastExtractSummary.set('Sample schema loaded.');
      },
      error: (err) => this.error.set(err?.error?.error ?? 'Failed to load sample schema.')
    });
  }

  extractPostgres(connectionString: string, schemaName = 'public'): void {
    this.extracting.set(true);
    this.error.set(null);
    this.lastExtractSummary.set(null);
    this.http
      .post<{ tables: { name: string }[] }>('/api/schema/extract-postgres', {
        connectionString,
        schemaName
      })
      .subscribe({
        next: (schema) => {
          this.setSchema(schema);
          this.lastExtractSummary.set(
            `Connected: ${schema.tables.length} table(s) extracted from "${schemaName}".`
          );
          this.extracting.set(false);
        },
        error: (err) => {
          this.error.set(err?.error?.error ?? 'Failed to connect / extract schema.');
          this.extracting.set(false);
        }
      });
  }

  exportSpringMetadata(): void {
    const schema = this.enrichedSchema();
    if (!schema) {
      this.error.set('No schema loaded. Extract a schema first.');
      return;
    }
    this.exporting.set(true);
    this.error.set(null);
    this.springMetadata.set(null);
    this.http
      .post<SpringMetadataPayload>('/api/schema/to-spring-metadata', {
        schema,
        options: { entityNamePrefix: 'Dynamic' }
      })
      .subscribe({
        next: (payload) => {
          this.springMetadata.set(payload);
          this.downloadJson('spring-entities-config.json', payload);
          this.exporting.set(false);
        },
        error: (err) => {
          this.error.set(err?.error?.error ?? 'Spring metadata export failed.');
          this.exporting.set(false);
        }
      });
  }

  private downloadJson(filename: string, data: unknown): void {
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
  }
}
