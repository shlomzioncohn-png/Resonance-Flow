import { describe, expect, it } from 'vitest'
import { shouldRetry } from '@/components/providers/QueryProvider'
import { ApiError } from '@/services/api'

describe('shouldRetry', () => {
  const networkError = new TypeError('Failed to fetch')

  it('does not retry a 4xx ApiError, since the request itself is wrong', () => {
    expect(shouldRetry(0, new ApiError('Not found', 404, '/x'))).toBe(false)
  })

  it('retries a 5xx ApiError, since the server may recover', () => {
    expect(shouldRetry(0, new ApiError('Service unavailable', 503, '/x'))).toBe(true)
  })

  it('retries a network error after the first failure', () => {
    expect(shouldRetry(0, networkError)).toBe(true)
  })

  it('stops retrying after two retries', () => {
    expect(shouldRetry(2, networkError)).toBe(false)
  })
})
